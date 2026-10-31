package com.wellnessz.gateway.queue;

import com.wellnessz.gateway.support.PathNames;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.support.AtomicFiles;
import com.wellnessz.secrets.AesGcmCipher;
import com.wellnessz.secrets.CryptoException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Durable, append-only, AES-256-GCM encrypted store-and-forward queue (contract §16.2).
 *
 * <p>Layout under the queue directory:</p>
 * <ul>
 *   <li>{@code seg-<first sequence>.wzq} – segment files; one record per line, each line
 *       {@code base64(iv || ciphertext || tag)} of the JSON item, with the segment file name as associated data so
 *       records cannot be moved between segments unnoticed;</li>
 *   <li>{@code acks.wzq} – encrypted acknowledgement records ({@code A <seq>} delivered, {@code D <seq>} dead-lettered);</li>
 *   <li>{@code state.json} – the highest sequence ever assigned (sequence numbers are never reused);</li>
 *   <li>{@code dead-letter/dlq.wzq} – encrypted dead-letter records (item + reason code), reported in the heartbeat;</li>
 *   <li>{@code QUARANTINED} – marker written by the {@code ISOLATE} command or a 401 isolated / revoked verdict.</li>
 * </ul>
 * Every append and acknowledgement is fsync-ed before the call returns, so an HL7 AA given on the strength of the
 * queue write survives a power loss. A segment is deleted only when every record in it was acknowledged or
 * dead-lettered. A torn final line (crash during write) is truncated on recovery; any other record that fails
 * authentication is counted as corrupt and reported, never silently forwarded.
 */
public class EncryptedJournal implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EncryptedJournal.class);
    private static final String ACK_FILE = "acks.wzq";
    private static final String ACK_AAD = "wz-gateway/acks";
    private static final String DLQ_AAD = "wz-gateway/dlq";
    private static final int ACK_COMPACT_THRESHOLD = 10_000;

    private final Path dir;
    private final AesGcmCipher cipher;
    private final Supplier<String> endpointId;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    private volatile long maxBytes;
    private final long segmentBytes;

    private final TreeMap<Long, Entry> pending = new TreeMap<>();
    private final Map<Path, Integer> liveBySegment = new HashMap<>();
    private final Map<Path, Long> segmentSizes = new LinkedHashMap<>();
    private final java.util.Set<Long> held = new java.util.HashSet<>();
    private Path activeSegment;
    private long nextSequence = 1;
    private long ackLines;
    private long deadLetters;
    private long corruptRecords;
    private boolean quarantined;

    private record Entry(long sequence, Path segment, long offset, int length, String deviceInstanceId,
                         Instant capturedAt) {
    }

    private record StoredItem(long sequence, String idempotencyKey, ItemKind kind, String deviceInstanceId,
                              Instant capturedAt, String contentType, Map<String, String> headers, byte[] body) {
    }

    private record DeadLetter(StoredItem item, String reason, Instant at) {
    }

    public EncryptedJournal(Path dir, AesGcmCipher cipher, Supplier<String> endpointId, long maxBytes,
                            long segmentBytes, Clock clock) {
        this.dir = dir;
        this.cipher = cipher;
        this.endpointId = endpointId;
        this.maxBytes = maxBytes;
        this.segmentBytes = segmentBytes;
        this.clock = clock;
        try {
            Files.createDirectories(dir.resolve("dead-letter"));
            recover();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to open the store-and-forward queue", e);
        }
    }

    // ------------------------------------------------------------------ public API

    /** Appends a message and fsyncs it. {@code idempotencyKey == null} → {@code "<endpointId>:<sequence>"}. */
    public QueueItem append(ItemKind kind, String deviceInstanceId, String contentType,
                            Map<String, String> headers, byte[] body, String idempotencyKey) {
        return append(kind, deviceInstanceId, contentType, headers, body, idempotencyKey, false);
    }

    /**
     * Appends a message; with {@code hold} the item is invisible to {@link #pending} until {@link #releaseHold} – used
     * while a listener forwards it synchronously to relay the core's acknowledgement (held state is in memory only, so
     * after a restart every item is pending again).
     */
    public synchronized QueueItem append(ItemKind kind, String deviceInstanceId, String contentType,
                                         Map<String, String> headers, byte[] body, String idempotencyKey,
                                         boolean hold) {
        long seq = nextSequence;
        String key = idempotencyKey != null ? idempotencyKey : endpointId.get() + ":" + seq;
        QueueItem item = new QueueItem(seq, key, kind, deviceInstanceId, clock.instant(), contentType, headers, body);
        Path segment = segmentFor(seq);
        byte[] line = encryptLine(toStored(item), PathNames.name(segment));
        if (totalBytes() + line.length > maxBytes) {
            throw new QueueFullException("store-and-forward queue full (" + maxBytes + " bytes)");
        }
        try {
            long offset = appendDurably(segment, line);
            segmentSizes.merge(segment, (long) line.length, Long::sum);
            nextSequence = seq + 1;
            pending.put(seq, new Entry(seq, segment, offset, line.length, deviceInstanceId, item.capturedAt()));
            liveBySegment.merge(segment, 1, Integer::sum);
            if (hold) {
                held.add(seq);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("queue write failed", e);
        }
        return item;
    }

    /** Makes a held item visible to the drain loop again. */
    public synchronized void releaseHold(long sequence) {
        held.remove(sequence);
    }

    /** Oldest pending items in sequence order (FIFO overall, hence FIFO per device instance). */
    public synchronized List<QueueItem> pending(int limit, long maxTotalBytes) {
        List<QueueItem> out = new ArrayList<>();
        List<Long> unreadable = new ArrayList<>();
        long total = 0;
        for (Entry e : pending.values()) {
            if (out.size() >= limit || (!out.isEmpty() && total + e.length() > maxTotalBytes)) {
                break;
            }
            if (held.contains(e.sequence())) {
                break; // FIFO: nothing newer may overtake an item that is being forwarded synchronously
            }
            QueueItem item = read(e);
            if (item != null) {
                out.add(item);
                total += e.length();
            } else {
                unreadable.add(e.sequence());
            }
        }
        for (Long seq : unreadable) {
            corruptRecords++;
            deadLetter(seq, "record_unreadable");
        }
        return out;
    }

    public synchronized QueueItem get(long sequence) {
        Entry e = pending.get(sequence);
        return e == null ? null : read(e);
    }

    public synchronized boolean isPending(long sequence) {
        return pending.containsKey(sequence);
    }

    /** {@code true} when an older item of the same device instance is still waiting (keeps per-instance FIFO). */
    public synchronized boolean hasOlderPending(long sequence, String deviceInstanceId) {
        for (Entry e : pending.headMap(sequence, false).values()) {
            if (java.util.Objects.equals(e.deviceInstanceId(), deviceInstanceId)) {
                return true;
            }
        }
        return false;
    }

    /** Records delivery (2xx {@code ACCEPTED} / {@code DUPLICATE}); the record is removed when its segment is drained. */
    public synchronized void acknowledge(long sequence) {
        Entry e = pending.remove(sequence);
        held.remove(sequence);
        if (e == null) {
            return;
        }
        writeAck("A " + sequence);
        release(e);
    }

    /** Moves an item to the local dead-letter file with a reason code (deterministic 4xx, retention expiry). */
    public synchronized void deadLetter(long sequence, String reason) {
        Entry e = pending.get(sequence);
        if (e == null) {
            return;
        }
        QueueItem item = read(e);
        try {
            if (item != null) {
                byte[] line = encrypt(json.writeValueAsString(new DeadLetter(toStored(item), reason, clock.instant())),
                        DLQ_AAD);
                appendDurably(dir.resolve("dead-letter").resolve("dlq.wzq"), line);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("dead-letter write failed", ex);
        }
        pending.remove(sequence);
        held.remove(sequence);
        writeAck("D " + sequence);
        deadLetters++;
        release(e);
        log.warn("queue item seq={} dead-lettered reason={}", sequence, reason);
    }

    /** Dead-letters items older than the retention window (≥ 48 h); returns how many expired. */
    public synchronized int expire(Duration retention) {
        Instant limit = clock.instant().minus(retention);
        List<Long> expired = pending.values().stream()
                .filter(e -> e.capturedAt().isBefore(limit)).map(Entry::sequence).toList();
        expired.forEach(seq -> deadLetter(seq, "retention_expired"));
        return expired.size();
    }

    public synchronized void quarantine(boolean on) {
        Path marker = dir.resolve("QUARANTINED");
        try {
            if (on) {
                AtomicFiles.write(marker, clock.instant().toString());
            } else {
                Files.deleteIfExists(marker);
            }
            quarantined = on;
        } catch (IOException e) {
            throw new UncheckedIOException("unable to change quarantine state", e);
        }
    }

    public synchronized boolean quarantined() {
        return quarantined;
    }

    public void setMaxBytes(long maxBytes) {
        this.maxBytes = maxBytes;
    }

    public synchronized QueueStats stats() {
        long bytes = totalBytes();
        Instant oldest = pending.isEmpty() ? null : pending.firstEntry().getValue().capturedAt();
        double pct = maxBytes <= 0 ? 0 : Math.round(bytes * 10000.0 / maxBytes) / 100.0;
        return new QueueStats(pending.size(), oldest, bytes, pct, nextSequence, deadLetters, corruptRecords,
                quarantined);
    }

    /** Reads back the dead-letter file (operator tooling / tests); returns {@code reason} per sequence. */
    public synchronized Map<Long, String> deadLetterReasons() {
        Map<Long, String> out = new LinkedHashMap<>();
        Path dlq = dir.resolve("dead-letter").resolve("dlq.wzq");
        if (!Files.exists(dlq)) {
            return out;
        }
        try {
            for (Line l : readLines(dlq)) {
                DeadLetter d = json.readValue(cipher.decrypt(l.text(), DLQ_AAD), DeadLetter.class);
                out.put(d.item().sequence(), d.reason());
            }
        } catch (IOException | CryptoException e) {
            throw new IllegalStateException("dead-letter file unreadable", e);
        }
        return out;
    }

    @Override
    public synchronized void close() {
        persistState();
    }

    // ------------------------------------------------------------------ internals

    private Path segmentFor(long seq) {
        if (activeSegment == null || segmentSizes.getOrDefault(activeSegment, 0L) >= segmentBytes) {
            Path previous = activeSegment;
            activeSegment = dir.resolve(String.format("seg-%020d.wzq", seq));
            if (previous != null && !liveBySegment.containsKey(previous)) {
                deleteSegment(previous);
            }
            segmentSizes.putIfAbsent(activeSegment, 0L);
            persistState();
        }
        return activeSegment;
    }

    private long totalBytes() {
        return segmentSizes.values().stream().mapToLong(Long::longValue).sum();
    }

    private void release(Entry e) {
        int left = liveBySegment.merge(e.segment(), -1, Integer::sum);
        if (left <= 0) {
            liveBySegment.remove(e.segment());
            if (!e.segment().equals(activeSegment)) {
                deleteSegment(e.segment());
            }
        }
        if (ackLines > ACK_COMPACT_THRESHOLD) {
            compactAcks();
        }
    }

    private void deleteSegment(Path segment) {
        persistState();
        try {
            Files.deleteIfExists(segment);
            segmentSizes.remove(segment);
        } catch (IOException ex) {
            log.warn("unable to delete drained segment {}", PathNames.name(segment));
        }
    }

    /** Keeps only acknowledgements that refer to records still present in a segment file. */
    private void compactAcks() {
        persistState();
        StringBuilder sb = new StringBuilder();
        long kept = 0;
        long minLive = segmentSizes.keySet().stream().mapToLong(EncryptedJournal::firstSequence).min().orElse(nextSequence);
        try {
            Path ackFile = dir.resolve(ACK_FILE);
            if (Files.exists(ackFile)) {
                for (Line l : readLines(ackFile)) {
                    String rec = cipher.decrypt(l.text(), ACK_AAD);
                    long seq = Long.parseLong(rec.substring(2));
                    if (seq >= minLive) {
                        sb.append(l.text()).append('\n');
                        kept++;
                    }
                }
            }
            AtomicFiles.write(ackFile, sb.toString());
            ackLines = kept;
        } catch (IOException | CryptoException | NumberFormatException e) {
            log.warn("acknowledgement compaction skipped: {}", e.getClass().getSimpleName());
        }
    }

    private void writeAck(String record) {
        try {
            appendDurably(dir.resolve(ACK_FILE), encrypt(record, ACK_AAD));
            ackLines++;
        } catch (IOException e) {
            throw new UncheckedIOException("acknowledgement write failed", e);
        }
    }

    private QueueItem read(Entry e) {
        try (FileChannel ch = FileChannel.open(e.segment(), StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(e.length());
            ch.position(e.offset());
            while (buf.hasRemaining() && ch.read(buf) > 0) {
                // read fully
            }
            String text = new String(buf.array(), 0, buf.position(), StandardCharsets.US_ASCII).trim();
            return fromStored(json.readValue(cipher.decrypt(text, PathNames.name(e.segment())),
                    StoredItem.class));
        } catch (IOException | CryptoException ex) {
            log.error("queue record seq={} unreadable: {}", e.sequence(), ex.getClass().getSimpleName());
            return null;
        }
    }

    private byte[] encryptLine(StoredItem item, String aad) {
        try {
            return encrypt(json.writeValueAsString(item), aad);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("queue item not serialisable", e);
        }
    }

    private byte[] encrypt(String plaintext, String aad) {
        return (cipher.encrypt(plaintext, aad) + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    private static long appendDurably(Path file, byte[] line) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            long offset = ch.size();
            ByteBuffer buf = ByteBuffer.wrap(line);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(false);
            return offset;
        }
    }

    private void persistState() {
        try {
            AtomicFiles.write(dir.resolve("state.json"), "{\"nextSequence\":" + nextSequence + "}");
        } catch (IOException e) {
            log.warn("unable to persist queue state");
        }
    }

    private synchronized void recover() throws IOException {
        quarantined = Files.exists(dir.resolve("QUARANTINED"));
        String state = AtomicFiles.readOrNull(dir.resolve("state.json"));
        if (state != null) {
            nextSequence = Math.max(nextSequence, json.readTree(state).path("nextSequence").asLong(1));
        }
        Map<Long, Character> acks = new HashMap<>();
        Path ackFile = dir.resolve(ACK_FILE);
        if (Files.exists(ackFile)) {
            for (Line l : readLines(ackFile)) {
                try {
                    String rec = cipher.decrypt(l.text(), ACK_AAD);
                    acks.put(Long.parseLong(rec.substring(2)), rec.charAt(0));
                    ackLines++;
                } catch (CryptoException | NumberFormatException | StringIndexOutOfBoundsException e) {
                    corruptRecords++;
                }
            }
            truncateTornTail(ackFile);
        }
        deadLetters = acks.values().stream().filter(c -> c == 'D').count();
        List<Path> segments;
        try (Stream<Path> s = Files.list(dir)) {
            segments = s.filter(p -> PathNames.name(p).matches("seg-\\d{20}\\.wzq")).sorted().toList();
        }
        for (Path segment : segments) {
            truncateTornTail(segment);
            String aad = PathNames.name(segment);
            int live = 0;
            for (Line l : readLines(segment)) {
                try {
                    StoredItem item = json.readValue(cipher.decrypt(l.text(), aad), StoredItem.class);
                    nextSequence = Math.max(nextSequence, item.sequence() + 1);
                    if (!acks.containsKey(item.sequence())) {
                        pending.put(item.sequence(), new Entry(item.sequence(), segment, l.offset(), l.length(),
                                item.deviceInstanceId(), item.capturedAt()));
                        live++;
                    }
                } catch (CryptoException | tools.jackson.core.JacksonException e) {
                    corruptRecords++;
                    log.error("queue segment {} contains a record that failed authentication", aad);
                }
            }
            segmentSizes.put(segment, Files.size(segment));
            if (live > 0) {
                liveBySegment.put(segment, live);
            }
            activeSegment = segment;
        }
        // drained, non-active segments left behind by a crash between acknowledgement and deletion
        for (Path segment : segments) {
            if (!liveBySegment.containsKey(segment) && !segment.equals(activeSegment)) {
                deleteSegment(segment);
            }
        }
        for (Long seq : acks.keySet()) {
            nextSequence = Math.max(nextSequence, seq + 1);
        }
        persistState();
        if (!pending.isEmpty() || corruptRecords > 0) {
            log.info("queue recovered: depth={} nextSequence={} corrupt={}", pending.size(), nextSequence,
                    corruptRecords);
        }
    }

    /** Removes a final line without its terminating newline (write interrupted by a crash). */
    private static void truncateTornTail(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long size = ch.size();
            if (size == 0) {
                return;
            }
            long pos = size - 1;
            ByteBuffer one = ByteBuffer.allocate(1);
            while (pos >= 0) {
                one.clear();
                ch.read(one, pos);
                if (one.get(0) == '\n') {
                    break;
                }
                pos--;
            }
            if (pos + 1 < size) {
                ch.truncate(pos + 1);
                ch.force(true);
            }
        }
    }

    private record Line(String text, long offset, int length) {
    }

    private static List<Line> readLines(Path file) throws IOException {
        List<Line> lines = new ArrayList<>();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ);
             InputStream in = new java.io.BufferedInputStream(Channels.newInputStream(ch), 65536)) {
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            long offset = 0;
            long pos = 0;
            int b;
            while ((b = in.read()) != -1) {
                pos++;
                if (b == '\n') {
                    if (cur.size() > 0) {
                        lines.add(new Line(cur.toString(StandardCharsets.US_ASCII), offset, (int) (pos - offset)));
                    }
                    cur.reset();
                    offset = pos;
                } else {
                    cur.write(b);
                }
            }
        }
        return lines;
    }

    private static long firstSequence(Path segment) {
        String name = PathNames.name(segment);
        return Long.parseLong(name.substring(4, 24));
    }

    private static StoredItem toStored(QueueItem i) {
        return new StoredItem(i.sequence(), i.idempotencyKey(), i.kind(), i.deviceInstanceId(), i.capturedAt(),
                i.contentType(), i.headers(), i.body());
    }

    private static QueueItem fromStored(StoredItem s) {
        return new QueueItem(s.sequence(), s.idempotencyKey(), s.kind(), s.deviceInstanceId(), s.capturedAt(),
                s.contentType(), s.headers(), s.body());
    }
}
