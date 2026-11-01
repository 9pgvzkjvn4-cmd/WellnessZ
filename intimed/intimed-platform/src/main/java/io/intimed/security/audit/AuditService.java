package io.intimed.security.audit;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.common.Paths2;
import io.intimed.security.crypto.MasterKey;
import io.intimed.security.privacy.PhiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tamper-evident audit trail (SC-08, IM-E06-BL-007): every record carries HMAC-SHA256(auditKey, prevHash | fields), so
 * altering, deleting or reordering a row breaks verification from that row on. Details are PHI-masked before they are
 * written. Each record is also forwarded as a JSON line to the SIEM sink ({@code <home>/siem/audit.jsonl}; Sentinel /
 * Chronicle / Splunk / QRadar collectors tail this file or receive it over syslog in DEV/UAT/PROD).
 */
@Service
public class AuditService {
    private static final Logger LOG = LoggerFactory.getLogger(AuditService.class);
    public static final String GENESIS = "0".repeat(64);
    private final JdbcClient jdbc;
    private final byte[] key = MasterKey.derive("audit-chain");
    private final Path siem = Paths2.dir("siem").resolve("audit.jsonl");

    public AuditService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public synchronized long record(String tenant, String actor, String action, String target, Object detail) {
        String det = detail == null ? null : PhiMasker.mask(detail instanceof String s ? s : Json.write(detail));
        String prev = jdbc.sql("select hash from audit_log order by seq desc limit 1").query(String.class).optional().orElse(GENESIS);
        Instant ts = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String hash = Hashing.hmacSha256(key, material(prev, tenant, ts, actor, action, target, det));
        jdbc.sql("insert into audit_log(tenant_id, ts, actor, action, target, detail, prev_hash, hash) values (:t, :ts, :a, :ac, :tg, :d, :p, :h)")
                .param("t", tenant).param("ts", java.sql.Timestamp.from(ts)).param("a", actor).param("ac", action)
                .param("tg", target).param("d", det).param("p", prev).param("h", hash).update();
        long seq = jdbc.sql("select max(seq) from audit_log").query(Long.class).single();
        ObjectNode line = Json.obj().put("seq", seq).put("tenant", tenant).put("ts", ts.toString()).put("actor", actor)
                .put("action", action).put("target", target).put("detail", det).put("hash", hash);
        try {
            Files.writeString(siem, Json.write(line) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("SIEM sink unavailable: {}", e.getMessage());
        }
        return seq;
    }

    private static String material(String prev, String tenant, Instant ts, String actor, String action, String target, String det) {
        return String.join("|", prev, tenant, ts.toString(), actor, action, target == null ? "" : target, det == null ? "" : det);
    }

    /** Re-computes the whole chain; returns {valid, records, brokenAt}. */
    public Map<String, Object> verify() {
        List<Map<String, Object>> rows = jdbc.sql("select seq, tenant_id, ts, actor, action, target, detail, prev_hash, hash from audit_log order by seq")
                .query((rs, i) -> {
                    Map<String, Object> m = new java.util.HashMap<>();
                    m.put("seq", rs.getLong(1)); m.put("tenant", rs.getString(2));
                    m.put("ts", rs.getTimestamp(3).toInstant()); m.put("actor", rs.getString(4)); m.put("action", rs.getString(5));
                    m.put("target", rs.getString(6)); m.put("detail", rs.getString(7)); m.put("prev", rs.getString(8)); m.put("hash", rs.getString(9));
                    return m;
                }).list();
        String prev = GENESIS;
        for (Map<String, Object> r : rows) {
            String expect = Hashing.hmacSha256(key, material(prev, (String) r.get("tenant"), (Instant) r.get("ts"), (String) r.get("actor"),
                    (String) r.get("action"), (String) r.get("target"), (String) r.get("detail")));
            if (!prev.equals(r.get("prev")) || !Hashing.equalsConstantTime(expect, (String) r.get("hash"))) {
                return Map.of("valid", false, "records", rows.size(), "brokenAt", r.get("seq"));
            }
            prev = (String) r.get("hash");
        }
        return Map.of("valid", true, "records", rows.size(), "head", prev);
    }

    public List<Map<String, Object>> recent(String tenant, int limit) {
        return new ArrayList<>(jdbc.sql("select seq, ts, actor, action, target, detail, hash from audit_log where tenant_id = :t order by seq desc limit :l")
                .param("t", tenant).param("l", limit)
                .query((rs, i) -> Map.<String, Object>of("seq", rs.getLong(1), "ts", rs.getTimestamp(2).toInstant().toString(),
                        "actor", rs.getString(3), "action", rs.getString(4), "target", String.valueOf(rs.getString(5)),
                        "detail", String.valueOf(rs.getString(6)), "hash", rs.getString(7))).list());
    }

    public List<Map<String, Object>> byTarget(String tenant, String target) {
        return new ArrayList<>(jdbc.sql("select seq, ts, actor, action, detail from audit_log where tenant_id = :t and target = :g order by seq")
                .param("t", tenant).param("g", target)
                .query((rs, i) -> Map.<String, Object>of("seq", rs.getLong(1), "ts", rs.getTimestamp(2).toInstant().toString(),
                        "actor", rs.getString(3), "action", rs.getString(4), "detail", String.valueOf(rs.getString(5)))).list());
    }
}
