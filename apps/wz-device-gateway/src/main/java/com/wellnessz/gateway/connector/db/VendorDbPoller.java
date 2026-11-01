package com.wellnessz.gateway.connector.db;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.AbstractConnector;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.core.SecretClient;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueFullException;
import com.wellnessz.gateway.support.AtomicFiles;
import com.wellnessz.gateway.support.Hashes;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * P2 vendor-database poller (Device Host agent role): connects read-only ({@link Connection#setReadOnly}, a read-only
 * database account, every transaction rolled back) and runs the configured {@code SELECT} with one {@code ?} parameter
 * bound to the persisted high-water mark. Each new row is queued as a one-row CSV file to
 * {@code /device-ingest/files} (format CSV) with the content SHA-256 as idempotency key; the watermark advances only
 * after the row is durably queued, so a crash may re-read a row but the core recognises the duplicate.
 * The credential comes from configuration ({@code ENC()/file:} via wz-secrets) or from a {@code credentialRef}
 * resolved on demand through {@code GET /device-ingest/secrets/{ref}}.
 */
public class VendorDbPoller extends AbstractConnector {

    private static final Logger log = LoggerFactory.getLogger(VendorDbPoller.class);

    private final GatewayProperties.DbPoller cfg;
    private final Path stateFile;
    private final SecretClient secrets;
    private final String sourceHash;
    private ScheduledExecutorService executor;

    public VendorDbPoller(GatewayProperties.DbPoller cfg, IngestSink sink, Clock clock, Path dataDir,
                          SecretClient secrets) {
        super(cfg.id(), cfg.deviceInstanceId(), sink, clock);
        validate(cfg);
        this.cfg = cfg;
        this.stateFile = dataDir.resolve("dbpollers").resolve(cfg.id() + ".watermark");
        this.secrets = secrets;
        this.sourceHash = Hashes.sha256Hex(cfg.url());
    }

    static void validate(GatewayProperties.DbPoller cfg) {
        if (cfg.url() == null || !cfg.url().startsWith("jdbc:")) {
            throw new IllegalArgumentException("db poller " + cfg.id() + ": jdbc url required");
        }
        String q = cfg.query() == null ? "" : cfg.query().trim();
        String lower = q.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select ") || lower.startsWith("with ")) || q.contains(";")
                || q.chars().filter(c -> c == '?').count() != 1) {
            throw new IllegalArgumentException("db poller " + cfg.id()
                    + ": query must be a single SELECT with exactly one '?' (the watermark)");
        }
        if (cfg.watermarkColumn() == null || cfg.watermarkColumn().isBlank()) {
            throw new IllegalArgumentException("db poller " + cfg.id() + ": watermark-column required");
        }
    }

    @Override
    public String type() {
        return "VENDOR_DB";
    }

    @Override
    public void start() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wz-" + id + "-poll");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                poll();
            } catch (SQLException | IOException | RuntimeException e) {
                error("poll_failed");
                log.warn("{} poll failed: {}", id, e.getClass().getSimpleName());
            }
        }, 0, cfg.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    public String watermark() throws IOException {
        String s = AtomicFiles.readOrNull(stateFile);
        return s == null ? cfg.initialWatermark() : s.trim();
    }

    /** One poll; returns the number of rows queued. */
    @SuppressFBWarnings(value = {"SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING", "SQL_INJECTION_JDBC"},
            justification = "The query is operator configuration of the vendor-database connector (validated as a single "
                    + "SELECT with one placeholder, executed on a read-only connection and account); the only runtime "
                    + "value, the watermark, is bound as a JDBC parameter.")
    public synchronized int poll() throws SQLException, IOException {
        String watermark = watermark();
        int queued = 0;
        try (Connection c = DriverManager.getConnection(cfg.url(), cfg.username(), password())) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(cfg.query())) {
                ps.setMaxRows(cfg.maxRows());
                if (watermark.matches("-?\\d{1,18}")) {
                    ps.setLong(1, Long.parseLong(watermark));
                } else {
                    ps.setString(1, watermark);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        header.append(i > 1 ? "," : "").append(csv(md.getColumnLabel(i)));
                    }
                    while (rs.next()) {
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.append(i > 1 ? "," : "").append(csv(rs.getString(i)));
                        }
                        String mark = rs.getString(cfg.watermarkColumn());
                        byte[] content = (header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8);
                        String sha = Hashes.sha256Hex(content);
                        try {
                            sink.capture(type(), ItemKind.FILE, deviceInstanceId, "text/csv; charset=UTF-8",
                                    Map.of("X-File-Name", id + "-" + safe(mark) + ".csv", "X-File-Format", "CSV",
                                            "X-File-Sha256", sha, "X-Source-Path-Hash", sourceHash),
                                    content, sha);
                        } catch (QueueFullException e) {
                            error("queue_full");
                            break;
                        }
                        if (mark != null) {
                            AtomicFiles.write(stateFile, mark);
                        }
                        queued++;
                        seen();
                    }
                }
            } finally {
                c.rollback();
            }
        }
        return queued;
    }

    /** One row of a historic retrieval: one-row CSV file, its SHA-256 and the row's watermark. */
    public record HistoricRow(byte[] content, String sha256, String fileName) {
    }

    /**
     * Historic retrieval (E31-HLR-08): runs the connector's read-only query with the watermark bound to {@code from} and keeps the
     * rows whose watermark lies at or before {@code to}. The persisted high-water mark is not touched; rows already sent by the
     * regular poll produce the same bytes and therefore the same content hash (the core recognises them as duplicates).
     */
    @SuppressFBWarnings(value = {"SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING", "SQL_INJECTION_JDBC"},
            justification = "Same operator-configured, validated read-only SELECT as poll(); the range bound is a JDBC parameter.")
    public synchronized java.util.List<HistoricRow> history(java.time.Instant from, java.time.Instant to) throws SQLException, IOException {
        java.util.List<HistoricRow> out = new java.util.ArrayList<>();
        try (Connection c = DriverManager.getConnection(cfg.url(), cfg.username(), password())) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(cfg.query())) {
                ps.setMaxRows(cfg.maxRows());
                ps.setTimestamp(1, java.sql.Timestamp.from(from));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    StringBuilder header = new StringBuilder();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        header.append(i > 1 ? "," : "").append(csv(md.getColumnLabel(i)));
                    }
                    while (rs.next()) {
                        java.sql.Timestamp mark = rs.getTimestamp(cfg.watermarkColumn());
                        if (mark != null && mark.toInstant().isAfter(to)) {
                            continue;
                        }
                        StringBuilder row = new StringBuilder();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.append(i > 1 ? "," : "").append(csv(rs.getString(i)));
                        }
                        byte[] content = (header + "\r\n" + row + "\r\n").getBytes(StandardCharsets.UTF_8);
                        out.add(new HistoricRow(content, Hashes.sha256Hex(content), id + "-" + safe(rs.getString(cfg.watermarkColumn())) + ".csv"));
                    }
                }
            } finally {
                c.rollback();
            }
        }
        return out;
    }

    public String sourceHash() {
        return sourceHash;
    }

    private String password() throws IOException {
        if (cfg.credentialRef() != null && !cfg.credentialRef().isBlank()) {
            return secrets.resolve(cfg.credentialRef());
        }
        return cfg.password();
    }

    static String csv(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    private static String safe(String mark) {
        return mark == null ? "null" : mark.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
