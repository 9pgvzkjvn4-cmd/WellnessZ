package com.wellnessz.gateway.connector.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.wellnessz.gateway.TestClock;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.config.GatewayProperties.PostAction;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;
import com.wellnessz.gateway.support.Hashes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class FileWatcherTest {

    @TempDir
    Path tmp;

    private final TestClock clock = new TestClock(Instant.parse("2026-10-31T09:00:00Z"));
    private Path export;
    private Path archive;
    private Path quarantine;
    private Path data;
    private EncryptedJournal journal;
    private IngestSink sink;

    @BeforeEach
    void setUp() throws Exception {
        export = Files.createDirectories(tmp.resolve("export"));
        archive = tmp.resolve("archive");
        quarantine = tmp.resolve("quarantine");
        data = tmp.resolve("data");
        journal = new EncryptedJournal(data.resolve("queue"), GatewayKeys.of(new byte[32]).cipher("j"),
                () -> "EP-TST-GW-01", 1 << 24, 1 << 20, clock);
        sink = new IngestSink(journal, null, new SimpleMeterRegistry());
    }

    private FileWatcher watcher(int stabilitySeconds, PostAction action) {
        return new FileWatcher(new GatewayProperties.Watcher("spiro-export", "DI-SPIRO-01", export, "*", archive,
                quarantine, action, stabilitySeconds, "ISO-8859-15", 1024 * 1024, Duration.ofSeconds(1)),
                sink, clock, data);
    }

    @Test
    void csvExportIsQueuedWithFileHeadersAndContentHashAsIdempotencyKey() throws Exception {
        byte[] csv = "subject,fev1,fvc\nSYN-0001,3.41,4.20\n".getBytes(StandardCharsets.UTF_8);
        Files.write(export.resolve("spiro_20261031.csv"), csv);
        FileWatcher w = watcher(0, PostAction.COPY);
        assertThat(w.poll()).isEqualTo(1);
        assertThat(w.poll()).isZero(); // processed once only
        List<QueueItem> items = journal.pending(10, Long.MAX_VALUE);
        assertThat(items).hasSize(1);
        QueueItem item = items.get(0);
        String sha = Hashes.sha256Hex(csv);
        assertThat(item.kind()).isEqualTo(ItemKind.FILE);
        assertThat(item.idempotencyKey()).isEqualTo(sha);
        assertThat(item.deviceInstanceId()).isEqualTo("DI-SPIRO-01");
        assertThat(item.headers()).containsEntry("X-File-Name", "spiro_20261031.csv")
                .containsEntry("X-File-Format", "CSV").containsEntry("X-File-Sha256", sha)
                .containsKey("X-Source-Path-Hash");
        assertThat(item.headers().get("X-Source-Path-Hash")).doesNotContain("export").hasSize(64);
        assertThat(item.body()).isEqualTo(csv);
        // COPY: the vendor's file is untouched, a copy is archived
        assertThat(export.resolve("spiro_20261031.csv")).exists();
        assertThat(files(archive)).extracting(p -> p.getFileName().toString()).containsExactly("spiro_20261031.csv");
    }

    @Test
    void sameContentIsNeverQueuedTwiceEvenAfterRestartOrUnderAnotherName() throws Exception {
        byte[] xml = "<?xml version=\"1.0\"?><result id=\"1\"/>".getBytes(StandardCharsets.UTF_8);
        Files.write(export.resolve("a.xml"), xml);
        assertThat(watcher(0, PostAction.COPY).poll()).isEqualTo(1);
        // restart: a new watcher instance reads the persisted ledger
        FileWatcher restarted = watcher(0, PostAction.COPY);
        assertThat(restarted.poll()).isZero();
        Files.write(export.resolve("a-copy.xml"), xml);
        assertThat(restarted.poll()).isZero();
        assertThat(journal.stats().depth()).isEqualTo(1);
    }

    @Test
    void fileIsProcessedOnlyAfterTheStabilityWindow() throws Exception {
        FileWatcher w = watcher(10, PostAction.COPY);
        Path f = export.resolve("bp.csv");
        Files.writeString(f, "sys,dia\n120,80\n");
        assertThat(w.poll()).isZero();
        clock.advance(Duration.ofSeconds(5));
        assertThat(w.poll()).isZero();
        clock.advance(Duration.ofSeconds(6));
        assertThat(w.poll()).isEqualTo(1);
    }

    @Test
    void growingFileRestartsTheStabilityWindow() throws Exception {
        FileWatcher w = watcher(10, PostAction.COPY);
        Path f = export.resolve("bp.csv");
        Files.writeString(f, "sys,dia\n");
        assertThat(w.poll()).isZero();
        clock.advance(Duration.ofSeconds(11));
        Files.writeString(f, "sys,dia\n120,80\n");
        assertThat(w.poll()).isZero();
        clock.advance(Duration.ofSeconds(11));
        assertThat(w.poll()).isEqualTo(1);
    }

    @Test
    void invalidAndUnsupportedFilesGoToQuarantine() throws Exception {
        Files.writeString(export.resolve("report.pdf"), "not a pdf");
        Files.writeString(export.resolve("notes.docx"), "x");
        Files.writeString(export.resolve("result.gdt"), "garbage");
        Files.write(export.resolve("empty.csv"), new byte[0]);
        FileWatcher w = watcher(0, PostAction.COPY);
        assertThat(w.poll()).isZero();
        assertThat(journal.stats().depth()).isZero();
        assertThat(files(quarantine)).extracting(p -> p.getFileName().toString())
                .containsExactlyInAnyOrder("report.pdf", "notes.docx", "result.gdt", "empty.csv");
        assertThat(w.status().errors()).isEqualTo(4);
    }

    @Test
    void validGdtAndPdfAreAccepted() throws Exception {
        Files.write(export.resolve("result.gdt"), "01380006310\r\n0158100000123\r\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(export.resolve("ecg.pdf"), "%PDF-1.7\n%synthetic\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(watcher(0, PostAction.COPY).poll()).isEqualTo(2);
        List<QueueItem> items = journal.pending(10, Long.MAX_VALUE);
        assertThat(items).extracting(i -> i.headers().get("X-File-Format")).containsExactly("PDF", "GDT");
        assertThat(items.get(1).contentType()).isEqualTo("text/plain; charset=ISO-8859-15");
    }

    @Test
    void moveModeMovesProcessedFilesIntoTheArchive() throws Exception {
        Files.writeString(export.resolve("w.csv"), "kg\n81.5\n");
        assertThat(watcher(0, PostAction.MOVE).poll()).isEqualTo(1);
        assertThat(export.resolve("w.csv")).doesNotExist();
        assertThat(files(archive)).hasSize(1);
    }

    @Test
    void temporaryAndHiddenFilesAreIgnored() throws Exception {
        Files.writeString(export.resolve("x.csv.part"), "a\n1\n");
        Files.writeString(export.resolve(".hidden.csv"), "a\n1\n");
        Files.writeString(export.resolve("y.tmp"), "a\n1\n");
        assertThat(watcher(0, PostAction.COPY).poll()).isZero();
        assertThat(files(quarantine)).isEmpty();
    }

    private static List<Path> files(Path root) throws Exception {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile).toList();
        }
    }
}
