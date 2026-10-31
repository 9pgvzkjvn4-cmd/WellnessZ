package com.wellnessz.gateway.connector.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import com.wellnessz.gateway.TestClock;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.crypto.GatewayKeys;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;
import com.wellnessz.gateway.support.Hashes;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class VendorDbPollerTest {

    @TempDir
    Path tmp;

    private final TestClock clock = new TestClock(Instant.parse("2026-10-31T12:00:00Z"));
    private EncryptedJournal journal;
    private IngestSink sink;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        journal = new EncryptedJournal(tmp.resolve("queue"), GatewayKeys.of(new byte[32]).cipher("j"),
                () -> "EP-TST-AG-01", 1 << 24, 1 << 20, clock);
        sink = new IngestSink(journal, null, new SimpleMeterRegistry());
        url = "jdbc:h2:" + tmp.resolve("vendor").toAbsolutePath() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE audiogram (id BIGINT PRIMARY KEY, subject VARCHAR(32), l1k INT, r1k INT, "
                    + "note VARCHAR(64))");
            s.execute("INSERT INTO audiogram VALUES (1, 'SYN-0001', 10, 15, 'ok'), (2, 'SYN-0002', 25, 20, "
                    + "'quote \"x\", comma')");
        }
    }

    private VendorDbPoller poller(String query) {
        Map<String, String> m = new HashMap<>();
        m.put("wz.gateway.db-pollers[0].id", "audio-db");
        m.put("wz.gateway.db-pollers[0].device-instance-id", "DI-AUDIO-01");
        m.put("wz.gateway.db-pollers[0].url", url);
        m.put("wz.gateway.db-pollers[0].username", "sa");
        m.put("wz.gateway.db-pollers[0].password", "");
        m.put("wz.gateway.db-pollers[0].query", query);
        m.put("wz.gateway.db-pollers[0].watermark-column", "ID");
        GatewayProperties p = new Binder(new MapConfigurationPropertySource(m)).bindOrCreate("wz.gateway",
                GatewayProperties.class);
        return new VendorDbPoller(p.dbPollers().get(0), sink, clock, tmp.resolve("data"), null);
    }

    @Test
    void newRowsAreQueuedOnceAsCsvAndTheHighWaterMarkIsPersisted() throws Exception {
        VendorDbPoller p = poller("SELECT id, subject, l1k, r1k, note FROM audiogram WHERE id > ? ORDER BY id");
        assertThat(p.poll()).isEqualTo(2);
        assertThat(p.watermark()).isEqualTo("2");
        assertThat(p.poll()).isZero();

        List<QueueItem> items = journal.pending(10, Long.MAX_VALUE);
        assertThat(items).hasSize(2);
        String csv = new String(items.get(1).body(), StandardCharsets.UTF_8);
        assertThat(csv).isEqualTo("ID,SUBJECT,L1K,R1K,NOTE\r\n2,SYN-0002,25,20,\"quote \"\"x\"\", comma\"\r\n");
        assertThat(items.get(1).kind()).isEqualTo(ItemKind.FILE);
        assertThat(items.get(1).headers()).containsEntry("X-File-Format", "CSV")
                .containsEntry("X-File-Name", "audio-db-2.csv")
                .containsEntry("X-File-Sha256", Hashes.sha256Hex(items.get(1).body()));
        assertThat(items.get(1).idempotencyKey()).isEqualTo(Hashes.sha256Hex(items.get(1).body()));

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement()) {
            s.execute("INSERT INTO audiogram VALUES (3, 'SYN-0003', 30, 35, 'new')");
        }
        // a restarted poller continues from the persisted watermark
        VendorDbPoller restarted = poller("SELECT id, subject, l1k, r1k, note FROM audiogram WHERE id > ? ORDER BY id");
        assertThat(restarted.poll()).isEqualTo(1);
        assertThat(restarted.watermark()).isEqualTo("3");
    }

    @Test
    void connectionIsReadOnly() throws Exception {
        VendorDbPoller p = poller("SELECT id FROM audiogram WHERE id > ? ORDER BY id");
        p.poll();
        try (Connection c = DriverManager.getConnection(url, "sa", "");
             Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT COUNT(*) FROM audiogram")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void onlySingleSelectStatementsWithOneWatermarkParameterAreAccepted() {
        assertThatThrownBy(() -> poller("DELETE FROM audiogram WHERE id > ?"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> poller("SELECT * FROM audiogram; DROP TABLE audiogram"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> poller("SELECT * FROM audiogram"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
