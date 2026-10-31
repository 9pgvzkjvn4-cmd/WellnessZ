package com.wellnessz.gateway.connector.file;

import com.wellnessz.gateway.support.PathNames;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.wellnessz.gateway.support.AtomicFiles;

/**
 * Durable de-duplication ledger of a watched folder: the SHA-256 of every file already queued (the de-duplication key)
 * with the time it was recorded. Content hashes only – never paths or names. Entries older than the horizon are pruned
 * on start.
 */
public class DedupLedger {

    private final Path file;
    private final Clock clock;
    private final Map<String, Long> entries = new HashMap<>();

    public DedupLedger(Path file, Clock clock, Duration horizon) {
        this.file = file;
        this.clock = clock;
        try {
            Files.createDirectories(PathNames.parent(file));
            if (Files.exists(file)) {
                long cutoff = clock.instant().minus(horizon).getEpochSecond();
                boolean pruned = false;
                for (String line : Files.readAllLines(file, StandardCharsets.US_ASCII)) {
                    String[] p = line.trim().split(" ");
                    if (p.length == 2 && p[0].matches("[0-9a-f]{64}") && p[1].matches("\\d{1,19}")) {
                        long at = Long.parseLong(p[1]);
                        if (at >= cutoff) {
                            entries.put(p[0], at);
                        } else {
                            pruned = true;
                        }
                    }
                }
                if (pruned) {
                    StringBuilder sb = new StringBuilder();
                    entries.forEach((k, v) -> sb.append(k).append(' ').append(v).append('\n'));
                    AtomicFiles.write(file, sb.toString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("unable to open the de-duplication ledger", e);
        }
    }

    public synchronized boolean contains(String sha256) {
        return entries.containsKey(sha256);
    }

    public synchronized void add(String sha256) {
        if (entries.containsKey(sha256)) {
            return;
        }
        long now = clock.instant().getEpochSecond();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap((sha256 + " " + now + "\n").getBytes(StandardCharsets.US_ASCII)));
            ch.force(false);
        } catch (IOException e) {
            throw new UncheckedIOException("unable to update the de-duplication ledger", e);
        }
        entries.put(sha256, now);
    }

    public synchronized int size() {
        return entries.size();
    }
}
