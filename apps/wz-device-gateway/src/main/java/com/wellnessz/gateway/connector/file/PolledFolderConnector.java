package com.wellnessz.gateway.connector.file;

import com.wellnessz.gateway.support.PathNames;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties.PostAction;
import com.wellnessz.gateway.connector.AbstractConnector;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.queue.QueueFullException;
import com.wellnessz.gateway.support.Hashes;

/**
 * Polling folder connector (polling rather than OS watch events: works on SMB shares and with vendor software that
 * writes in place). A file is processed once its size and modification time have been stable for the stability window.
 * After queuing, the file is copied (default – the vendor's file is never touched) or moved to the archive folder;
 * files that cannot be accepted go to the quarantine folder. The content SHA-256 is the de-duplication key, persisted
 * in a {@link DedupLedger} and used as the {@code Idempotency-Key}, so a file is never forwarded twice.
 */
public abstract class PolledFolderConnector extends AbstractConnector {

    private static final Logger log = LoggerFactory.getLogger(PolledFolderConnector.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    /** Outcome of processing a stable file. */
    protected enum Result { QUEUED, DUPLICATE, QUARANTINE, RETRY_LATER }

    protected record Decision(Result result, String reason) {
        public static Decision queued() {
            return new Decision(Result.QUEUED, null);
        }

        public static Decision quarantine(String reason) {
            return new Decision(Result.QUARANTINE, reason);
        }
    }

    private record Observation(long size, long mtime, Instant stableSince) {
    }

    protected final Path folder;
    private final PathMatcher matcher;
    private final Path archive;
    private final Path quarantine;
    private final PostAction postAction;
    private final int stabilitySeconds;
    protected final long maxFileBytes;
    private final Duration pollInterval;
    protected final DedupLedger ledger;
    protected final String sourcePathHash;
    private final Map<Path, Observation> observations = new HashMap<>();
    private final Map<Path, String> done = new HashMap<>();
    private ScheduledExecutorService executor;

    protected PolledFolderConnector(String id, String deviceInstanceId, IngestSink sink, Clock clock, Path folder,
                                    String glob, Path archive, Path quarantine, PostAction postAction,
                                    int stabilitySeconds, long maxFileBytes, Duration pollInterval, Path dataDir) {
        super(id, deviceInstanceId, sink, clock);
        if (folder == null) {
            throw new IllegalArgumentException("connector " + id + ": path is required");
        }
        this.folder = folder.toAbsolutePath().normalize();
        this.matcher = FileSystems.getDefault().getPathMatcher("glob:" + (glob == null ? "*" : glob));
        this.archive = archive == null ? null : archive.toAbsolutePath().normalize();
        this.quarantine = quarantine == null ? null : quarantine.toAbsolutePath().normalize();
        this.postAction = postAction == null ? PostAction.COPY : postAction;
        this.stabilitySeconds = Math.max(0, stabilitySeconds);
        this.maxFileBytes = maxFileBytes;
        this.pollInterval = pollInterval;
        this.ledger = new DedupLedger(dataDir.resolve("watchers").resolve(id + ".ledger"), clock, Duration.ofDays(180));
        this.sourcePathHash = Hashes.sha256Hex(this.folder.toString());
        if (this.archive != null && this.archive.startsWith(this.folder) && "*".equals(glob)) {
            log.warn("{}: archive folder is inside the watched folder; it is excluded from scanning", id);
        }
    }

    @Override
    public void start() throws IOException {
        Files.createDirectories(folder);
        if (archive != null) {
            Files.createDirectories(archive);
        }
        if (quarantine != null) {
            Files.createDirectories(quarantine);
        }
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wz-" + id + "-poll");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::safePoll, 0, pollInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void safePoll() {
        try {
            poll();
        } catch (RuntimeException | IOException e) {
            error("poll_failed");
            log.warn("{} poll failed: {}", id, e.getClass().getSimpleName());
        }
    }

    /** One scan of the folder; returns the number of files queued. Public for tests and the status page. */
    public synchronized int poll() throws IOException {
        int queued = 0;
        for (Path file : stableFiles()) {
            String key = fingerprint(file);
            if (key == null || key.equals(done.get(file))) {
                continue;
            }
            Decision d;
            byte[] content = null;
            long size = Files.size(file);
            if (size > maxFileBytes) {
                d = Decision.quarantine("too_large");
            } else if (size == 0) {
                d = Decision.quarantine("empty");
            } else {
                content = Files.readAllBytes(file);
                String sha = Hashes.sha256Hex(content);
                if (ledger.contains(sha)) {
                    d = new Decision(Result.DUPLICATE, null);
                } else {
                    try {
                        d = process(file, content, sha);
                    } catch (QueueFullException e) {
                        d = new Decision(Result.RETRY_LATER, "queue_full");
                    }
                    if (d.result() == Result.QUEUED || d.result() == Result.QUARANTINE) {
                        ledger.add(sha);
                    }
                }
            }
            switch (d.result()) {
                case QUEUED -> {
                    queued++;
                    seen();
                    afterSuccess(file);
                    done.put(file, key);
                }
                case DUPLICATE -> {
                    if (postAction == PostAction.MOVE) {
                        afterSuccess(file); // COPY mode: already archived when first queued
                    }
                    done.put(file, key);
                }
                case QUARANTINE -> {
                    error(d.reason());
                    toQuarantine(file);
                    done.put(file, key);
                    log.warn("{}: file quarantined ({})", id, d.reason());
                }
                case RETRY_LATER -> error(d.reason());
            }
        }
        done.keySet().removeIf(p -> !Files.exists(p, LinkOption.NOFOLLOW_LINKS));
        return queued;
    }

    /** Validates and queues one file; the caller records the hash in the ledger on QUEUED / QUARANTINE. */
    protected abstract Decision process(Path file, byte[] content, String sha256);

    private List<Path> stableFiles() throws IOException {
        List<Path> stable = new ArrayList<>();
        Instant now = clock.instant();
        Map<Path, Observation> current = new HashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String name = PathNames.name(p);
                if (name.startsWith(".") || name.endsWith(".tmp") || name.endsWith(".part")
                        || !matcher.matches(Path.of(name))) {
                    continue;
                }
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile()) {
                    continue; // directories (archive / quarantine) and symbolic links are never followed
                }
                long mtime = a.lastModifiedTime().toMillis();
                Observation prev = observations.get(p);
                Observation obs = prev != null && prev.size() == a.size() && prev.mtime() == mtime ? prev
                        : new Observation(a.size(), mtime, now);
                current.put(p, obs);
                if (!Duration.between(obs.stableSince(), now).minusSeconds(stabilitySeconds).isNegative()) {
                    stable.add(p);
                }
            }
        }
        observations.clear();
        observations.putAll(current);
        stable.sort(null);
        return stable;
    }

    private static String fingerprint(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.size() + ":" + a.lastModifiedTime().toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    private void afterSuccess(Path file) throws IOException {
        if (archive != null) {
            transfer(file, archive.resolve(DAY.format(clock.instant())));
        } else if (postAction == PostAction.MOVE) {
            log.debug("{}: MOVE without archive folder – file left in place", id);
        }
    }

    private void toQuarantine(Path file) throws IOException {
        if (quarantine != null) {
            transfer(file, quarantine);
        }
    }

    private void transfer(Path file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path target = targetDir.resolve(PathNames.name(file));
        if (Files.exists(target)) {
            target = targetDir.resolve(clock.millis() + "-" + PathNames.name(file));
        }
        if (postAction == PostAction.MOVE) {
            Files.move(file, target);
        } else {
            Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    /** ASCII-only, header-safe file name (CR/LF and non-ASCII replaced). */
    protected static String headerSafe(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            sb.append(c >= 0x20 && c < 0x7f ? c : '_');
        }
        return sb.length() > 200 ? sb.substring(0, 200) : sb.toString();
    }
}
