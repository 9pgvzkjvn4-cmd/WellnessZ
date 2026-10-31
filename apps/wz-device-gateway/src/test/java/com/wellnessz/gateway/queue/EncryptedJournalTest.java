package com.wellnessz.gateway.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wellnessz.gateway.TestClock;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.secrets.AesGcmCipher;

class EncryptedJournalTest {

    @TempDir
    Path dir;

    private final GatewayKeys keys = GatewayKeys.of(new byte[32]);
    private final TestClock clock = new TestClock(Instant.parse("2026-10-31T08:00:00Z"));

    private EncryptedJournal open(long maxBytes, long segmentBytes) {
        return open(keys.cipher(GatewayKeys.JOURNAL_PURPOSE), maxBytes, segmentBytes);
    }

    private EncryptedJournal open(AesGcmCipher cipher, long maxBytes, long segmentBytes) {
        return new EncryptedJournal(dir, cipher, () -> "EP-TST-GW-01", maxBytes, segmentBytes, clock);
    }

    private static byte[] hl7(int n) {
        return ("MSH|^~\\&|DEV|||||||" + n + "|P|2.5\rPID|1||SYN-" + n + "||Synthetic^Person\r")
                .getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void recordsAreEncryptedAtRestAndAssignedIdempotencyKeys() throws Exception {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        QueueItem item = j.append(ItemKind.HL7V2, "DI-1", "application/hl7-v2", Map.of(), hl7(1), null);
        assertThat(item.sequence()).isEqualTo(1);
        assertThat(item.idempotencyKey()).isEqualTo("EP-TST-GW-01:1");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String raw = Files.readString(f, StandardCharsets.ISO_8859_1);
                assertThat(raw).doesNotContain("Synthetic").doesNotContain("SYN-1").doesNotContain("MSH");
            }
        }
        assertThat(new String(j.get(1).body(), StandardCharsets.ISO_8859_1)).contains("Synthetic^Person");
    }

    @Test
    void pendingItemsSurviveRestartAndAcknowledgedOnesDoNot() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        for (int i = 1; i <= 5; i++) {
            j.append(ItemKind.HL7V2, "DI-1", "application/hl7-v2", Map.of(), hl7(i), null);
        }
        j.acknowledge(1);
        j.acknowledge(3);
        j.close();

        EncryptedJournal reopened = open(1 << 20, 1 << 20);
        List<QueueItem> pending = reopened.pending(100, Long.MAX_VALUE);
        assertThat(pending).extracting(QueueItem::sequence).containsExactly(2L, 4L, 5L);
        assertThat(pending).extracting(QueueItem::idempotencyKey)
                .containsExactly("EP-TST-GW-01:2", "EP-TST-GW-01:4", "EP-TST-GW-01:5");
        assertThat(new String(pending.get(0).body(), StandardCharsets.ISO_8859_1)).contains("SYN-2");
        QueueItem next = reopened.append(ItemKind.HL7V2, "DI-1", "application/hl7-v2", Map.of(), hl7(6), null);
        assertThat(next.sequence()).isEqualTo(6);
    }

    @Test
    void sequenceIsNeverReusedEvenWhenEverythingWasDeliveredAndSegmentsWereDeleted() {
        EncryptedJournal j = open(1 << 20, 4096);
        for (int i = 1; i <= 40; i++) {
            QueueItem it = j.append(ItemKind.HL7V2, "DI-1", "application/hl7-v2", Map.of(), hl7(i), null);
            j.acknowledge(it.sequence());
        }
        j.close();
        EncryptedJournal reopened = open(1 << 20, 4096);
        assertThat(reopened.stats().depth()).isZero();
        assertThat(reopened.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(41), null).sequence()).isEqualTo(41);
    }

    @Test
    void drainedSegmentsAreDeleted() throws Exception {
        EncryptedJournal j = open(1 << 20, 4096);
        for (int i = 1; i <= 60; i++) {
            j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(i), null);
        }
        long segmentsBefore = segments();
        assertThat(segmentsBefore).isGreaterThan(3);
        for (int i = 1; i <= 60; i++) {
            j.acknowledge(i);
        }
        assertThat(segments()).isLessThanOrEqualTo(1);
        assertThat(j.stats().depth()).isZero();
    }

    @Test
    void tamperedRecordIsDetectedAndNeverForwarded() throws Exception {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(2), null);
        j.close();
        Path seg = segmentFiles().get(0);
        List<String> lines = Files.readAllLines(seg, StandardCharsets.US_ASCII);
        char[] c = lines.get(0).toCharArray();
        c[30] = c[30] == 'A' ? 'B' : 'A';
        Files.writeString(seg, new String(c) + "\n" + lines.get(1) + "\n", StandardCharsets.US_ASCII);

        EncryptedJournal reopened = open(1 << 20, 1 << 20);
        assertThat(reopened.stats().corruptRecords()).isEqualTo(1);
        assertThat(reopened.pending(10, Long.MAX_VALUE)).extracting(QueueItem::sequence).containsExactly(2L);
    }

    @Test
    void wrongKeyCannotReadTheQueue() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null);
        j.close();
        byte[] other = new byte[32];
        other[0] = 1;
        EncryptedJournal foreign = open(GatewayKeys.of(other).cipher(GatewayKeys.JOURNAL_PURPOSE), 1 << 20, 1 << 20);
        assertThat(foreign.stats().depth()).isZero();
        assertThat(foreign.stats().corruptRecords()).isEqualTo(1);
    }

    @Test
    void tornFinalLineFromACrashIsTruncated() throws Exception {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null);
        j.close();
        Files.writeString(segmentFiles().get(0), "QUJDREVG", StandardCharsets.US_ASCII, StandardOpenOption.APPEND);
        EncryptedJournal reopened = open(1 << 20, 1 << 20);
        assertThat(reopened.stats().corruptRecords()).isZero();
        assertThat(reopened.stats().depth()).isEqualTo(1);
        reopened.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(2), null);
        reopened.close();
        assertThat(open(1 << 20, 1 << 20).stats().depth()).isEqualTo(2);
    }

    @Test
    void capacityLimitRefusesNewItemsInsteadOfDroppingOldOnes() {
        EncryptedJournal j = open(2000, 1 << 20);
        assertThatThrownBy(() -> {
            for (int i = 1; i <= 100; i++) {
                j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(i), null);
            }
        }).isInstanceOf(QueueFullException.class);
        int depth = j.stats().depth();
        assertThat(depth).isPositive();
        assertThat(j.pending(100, Long.MAX_VALUE).get(0).sequence()).isEqualTo(1);
        assertThat(j.stats().capacityPct()).isGreaterThan(70);
    }

    @Test
    void itemsOlderThanRetentionAreDeadLetteredNotLost() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null);
        clock.advance(Duration.ofHours(47));
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(2), null);
        assertThat(j.expire(Duration.ofHours(48))).isZero();
        clock.advance(Duration.ofHours(2));
        assertThat(j.expire(Duration.ofHours(48))).isEqualTo(1);
        assertThat(j.pending(10, Long.MAX_VALUE)).extracting(QueueItem::sequence).containsExactly(2L);
        assertThat(j.deadLetterReasons()).containsEntry(1L, "retention_expired");
        assertThat(j.stats().deadLetters()).isEqualTo(1);
    }

    @Test
    void quarantineStateIsDurable() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.quarantine(true);
        j.close();
        assertThat(open(1 << 20, 1 << 20).quarantined()).isTrue();
    }

    @Test
    void perInstanceFifoCheck() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null);
        QueueItem b = j.append(ItemKind.HL7V2, "DI-2", "x", Map.of(), hl7(2), null);
        QueueItem c = j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(3), null);
        assertThat(j.hasOlderPending(b.sequence(), "DI-2")).isFalse();
        assertThat(j.hasOlderPending(c.sequence(), "DI-1")).isTrue();
    }

    @Test
    void explicitIdempotencyKeyIsKept() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        QueueItem it = j.append(ItemKind.FILE, "DI-1", "text/csv", Map.of("X-File-Format", "CSV"),
                "a,b".getBytes(StandardCharsets.UTF_8), "ab".repeat(32));
        assertThat(it.idempotencyKey()).isEqualTo("ab".repeat(32));
        assertThat(j.get(it.sequence()).headers()).containsEntry("X-File-Format", "CSV");
    }

    @Test
    void heldItemIsInvisibleToTheDrainUntilReleasedAndBlocksNewerOnes() {
        EncryptedJournal j = open(1 << 20, 1 << 20);
        QueueItem held = j.append(ItemKind.HL7V2, "DI-1", "x", Map.of(), hl7(1), null, true);
        j.append(ItemKind.HL7V2, "DI-2", "x", Map.of(), hl7(2), null);
        assertThat(j.pending(10, Long.MAX_VALUE)).isEmpty();
        assertThat(j.isPending(held.sequence())).isTrue();
        j.releaseHold(held.sequence());
        assertThat(j.pending(10, Long.MAX_VALUE)).extracting(QueueItem::sequence).containsExactly(1L, 2L);
        j.close();
        // holds are never persisted
        EncryptedJournal reopened = open(1 << 20, 1 << 20);
        assertThat(reopened.pending(10, Long.MAX_VALUE)).hasSize(2);
    }

    private long segments() throws Exception {
        return segmentFiles().size();
    }

    private List<Path> segmentFiles() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith("seg-")).sorted().toList();
        }
    }
}
