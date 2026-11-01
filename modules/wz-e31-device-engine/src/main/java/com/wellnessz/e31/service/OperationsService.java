package com.wellnessz.e31.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.gate.GateCatalogue;
import com.wellnessz.e31.pipeline.PipelineProcessor;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.store.RawPayloadStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.security.audit.AuditService;

/**
 * Operations API of the engine (E31-HLR-11, E31-F11, contract section 12): dead-letter triage and replay (no duplicates –
 * deterministic observation ids and idempotent sinks), bulk replay, connector health, metrics, alerts, archive hash
 * verification, tenants and quotas, and the gate catalogue with sign-off status. Everything here is PHI-free.
 */
@Service("e31OperationsService")
public class OperationsService {

    private final MessageStore messages;
    private final RawPayloadStore raw;
    private final PipelineProcessor processor;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public OperationsService(MessageStore messages, RawPayloadStore raw, PipelineProcessor processor, JdbcTemplate jdbc, AuditService audit,
                             Clock clock) {
        this.messages = messages;
        this.raw = raw;
        this.processor = processor;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ dead letters and replay

    public List<MessageStore.DeadLetter> deadLetters(String stage, String status) {
        return messages.deadLetters(stage, status, 500);
    }

    public Map<String, Object> replayDeadLetter(String actor, UUID id, String driverVersion) {
        MessageStore.DeadLetter dl = messages.deadLetter(id).orElseThrow(() -> ApiException.notFound("Dead letter"));
        if (!"OPEN".equals(dl.status())) {
            throw ApiException.conflict("already_resolved", "The dead letter is no longer open");
        }
        processor.tx().executeWithoutResult(s -> {
            messages.resetForReplay(dl.messageId(), null);
            messages.resolveDeadLetter(id, "REPLAYED", actor, driverVersion == null ? "replay" : "replay with " + driverVersion);
        });
        PipelineProcessor.Outcome o = processor.process(dl.messageId(), new PipelineProcessor.Options(driverVersion));
        audit.record("DEVICE_DLQ_REPLAY", "DEVICE_DEAD_LETTER", id.toString(), AuditService.Outcome.SUCCESS, o.status());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deadLetterId", id);
        out.put("messageId", dl.messageId());
        out.put("status", o.status());
        out.put("observationIds", o.observationIds());
        if (o.errorCode() != null) {
            out.put("errorCode", o.errorCode());
        }
        return out;
    }

    public Map<String, Object> discardDeadLetter(String actor, UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ApiException.badRequest("validation_failed", "A reason is required");
        }
        MessageStore.DeadLetter dl = messages.deadLetter(id).orElseThrow(() -> ApiException.notFound("Dead letter"));
        processor.tx().executeWithoutResult(s -> {
            messages.resolveDeadLetter(id, "DISCARDED", actor, reason.length() > 500 ? reason.substring(0, 500) : reason);
            MessageStore.MessageRow m = messages.find(dl.messageId()).orElseThrow();
            messages.update(m.id(), m.stage(), "DISCARDED", m.attempts(), null, m.errorCode(), null, null);
            raw.retainAsUnfiled(m.rawObjectId());
        });
        audit.record("DEVICE_DLQ_DISCARD", "DEVICE_DEAD_LETTER", id.toString(), AuditService.Outcome.SUCCESS, null);
        return Map.of("deadLetterId", id, "status", "DISCARDED");
    }

    /** Bulk replay of a time range from the stored raw payloads; already routed observations are not duplicated. */
    public Map<String, Object> replayRange(String actor, Instant from, Instant to, String deviceInstanceId, String stageFrom) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw ApiException.badRequest("validation_failed", "from must be before to");
        }
        String stage = stageFrom == null ? "PARSE" : stageFrom;
        if (!List.of("PARSE", "NORMALISE").contains(stage)) {
            throw ApiException.badRequest("validation_failed", "stageFrom must be PARSE or NORMALISE");
        }
        List<MessageStore.MessageRow> rows = messages.between(from, to, deviceInstanceId, EngineSettings.DEFAULT_TENANT);
        UUID job = UUID.randomUUID();
        jdbc.update("insert into deviceint.replay_job (id, requested_by, from_at, to_at, device_instance_id, stage_from, messages, created_at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                job, actor, Timestamp.from(from), Timestamp.from(to), deviceInstanceId, stage, rows.size(), Timestamp.from(clock.instant()));
        int routed = 0;
        for (MessageStore.MessageRow m : rows) {
            processor.tx().executeWithoutResult(s -> messages.resetForReplay(m.id(), null));
            PipelineProcessor.Outcome o = processor.process(m.id(), PipelineProcessor.Options.DEFAULT);
            routed += o.observationStatuses().stream().filter("ROUTED"::equals).count();
        }
        audit.record("DEVICE_REPLAY_RANGE", "DEVICE_REPLAY", job.toString(), AuditService.Outcome.SUCCESS, "messages=" + rows.size());
        return Map.of("jobId", job, "messages", rows.size(), "routedObservations", routed);
    }

    // ------------------------------------------------------------------ connector health, metrics, alerts

    public record ConnectorHealth(String deviceInstanceId, String endpointId, String siteCode, String option, String state, Instant lastSeenAt,
                                  long messages24h, long errors24h, Long p95LatencyMillis, long unmatchedOpen, long deadLetters) {
    }

    public List<ConnectorHealth> connectors(String siteCode, String state) {
        Instant now = clock.instant();
        List<ConnectorHealth> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                select device_instance_id, endpoint_id, site_code, option_code, last_seen_at, endpoint_status from deviceint.connector_state
                 where (?::text is null or site_code = ?) order by site_code, device_instance_id""", siteCode, siteCode)) {
            ConnectorHealth h = health((String) r.get("device_instance_id"), (String) r.get("endpoint_id"), (String) r.get("site_code"),
                    (String) r.get("option_code"), ts(r.get("last_seen_at")), (String) r.get("endpoint_status"), now);
            if (state == null || state.equals(h.state())) {
                out.add(h);
            }
        }
        return out;
    }

    /** Last heartbeat of a gateway / agent endpoint, {@code null} when it never sent one. */
    public Instant lastHeartbeat(String endpointId) {
        return jdbc.query("select last_heartbeat_at from deviceint.gateway_state where endpoint_id = ?",
                rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null, endpointId);
    }

    public ConnectorHealth health(String deviceInstanceId) {
        Map<String, Object> r = jdbc.queryForList("select * from deviceint.connector_state where device_instance_id = ?", deviceInstanceId)
                .stream().findFirst().orElse(Map.of());
        return health(deviceInstanceId, (String) r.get("endpoint_id"), (String) r.get("site_code"), (String) r.get("option_code"),
                ts(r.get("last_seen_at")), (String) r.get("endpoint_status"), clock.instant());
    }

    ConnectorHealth health(String id, String endpoint, String site, String option, Instant lastSeen, String endpointStatus, Instant now) {
        Timestamp since = Timestamp.from(now.minus(Duration.ofHours(24)));
        long msgs = count("select count(*) from deviceint.message where device_instance_id = ? and received_at >= ?", id, since);
        long errors = count("select count(*) from deviceint.message where device_instance_id = ? and received_at >= ? and status in ('DEAD_LETTER','RETRY')",
                id, since);
        long unmatched = count("select count(*) from deviceint.unmatched_item where device_instance_id = ? and status = 'OPEN'", id);
        long dead = count("select count(*) from deviceint.dead_letter where device_instance_id = ? and status = 'OPEN'", id);
        Long p95 = jdbc.queryForObject("""
                select (percentile_cont(0.95) within group (order by extract(epoch from (o.created_at - m.received_at)) * 1000))::bigint
                  from deviceint.observation o join deviceint.message m on m.id = o.message_id where o.device_instance_id = ? and o.created_at >= ?""",
                Long.class, id, since);
        String state;
        Instant heartbeat = endpoint == null ? null : jdbc.query("select last_heartbeat_at from deviceint.gateway_state where endpoint_id = ?",
                rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, endpoint);
        Instant seen = heartbeat != null && (lastSeen == null || heartbeat.isAfter(lastSeen)) ? heartbeat : lastSeen;
        if ("ISOLATED".equals(endpointStatus) || "REVOKED".equals(endpointStatus)) {
            state = "ISOLATED";
        } else if (seen == null || seen.isBefore(now.minus(Duration.ofMinutes(30)))) {
            state = "SILENT";
        } else if (seen.isBefore(now.minus(Duration.ofMinutes(5)))) {
            state = "STALE";
        } else if (msgs >= 10 && errors * 20 > msgs) {
            state = "DEGRADED";
        } else {
            state = "HEALTHY";
        }
        return new ConnectorHealth(id, endpoint, site, option, state, seen, msgs, errors, p95, unmatched, dead);
    }

    long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    static Instant ts(Object o) {
        return o instanceof Timestamp t ? t.toInstant() : o instanceof java.time.OffsetDateTime odt ? odt.toInstant() : null;
    }

    public Map<String, Object> metrics(String window) {
        Duration d = "P1D".equals(window) ? Duration.ofDays(1) : Duration.ofHours(1);
        Timestamp since = Timestamp.from(clock.instant().minus(d));
        long msgs = count("select count(*) from deviceint.message where received_at >= ? and test_traffic = false", since);
        long parseErrors = count("select count(*) from deviceint.dead_letter where first_failed_at >= ? and stage = 'PARSE'", since);
        long observations = count("select count(*) from deviceint.observation where created_at >= ? and test_traffic = false", since);
        long blocked = count("select count(*) from deviceint.observation where created_at >= ? and status = 'BLOCKED'", since);
        long unmatched = count("select count(*) from deviceint.unmatched_item where received_at >= ?", since);
        Map<String, Object> lat = jdbc.queryForMap("""
                select coalesce((percentile_cont(0.5) within group (order by extract(epoch from (o.created_at - m.received_at)) * 1000))::bigint, 0) as p50,
                       coalesce((percentile_cont(0.95) within group (order by extract(epoch from (o.created_at - m.received_at)) * 1000))::bigint, 0) as p95
                  from deviceint.observation o join deviceint.message m on m.id = o.message_id where o.created_at >= ?""", since);
        Long perSecond = jdbc.queryForObject("""
                select coalesce(max(c), 0) from (select count(*) as c from deviceint.message where received_at >= ?
                 group by date_trunc('second', received_at)) x""", Long.class, since);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("window", d.toString());
        out.put("messages", msgs);
        out.put("perSecondPeak", perSecond == null ? Long.valueOf(0) : perSecond);
        out.put("latencyP50Ms", lat.get("p50"));
        out.put("latencyP95Ms", lat.get("p95"));
        out.put("parseErrorRate", msgs == 0 ? 0.0 : (double) parseErrors / msgs);
        out.put("validationBlockRate", observations == 0 ? 0.0 : (double) blocked / observations);
        out.put("unmatchedRate", observations == 0 ? 0.0 : (double) unmatched / observations);
        long[] u = new long[] {count("select count(*) from deviceint.unmatched_item where status = 'OPEN'"),
            count("select count(*) from deviceint.unmatched_item where status = 'OPEN' and sla_due_at < ?", Timestamp.from(clock.instant()))};
        out.put("unmatchedOpen", u[0]);
        out.put("slaBreaches", u[1]);
        out.put("deadLettersOpen", count("select count(*) from deviceint.dead_letter where status = 'OPEN'"));
        return out;
    }

    public record Alert(String type, String deviceInstanceId, String siteCode, Instant since, String level) {
    }

    public List<Alert> alerts() {
        Instant now = clock.instant();
        List<Alert> out = new ArrayList<>();
        for (ConnectorHealth h : connectors(null, null)) {
            switch (h.state()) {
                case "SILENT" -> out.add(new Alert("SILENT", h.deviceInstanceId(), h.siteCode(), h.lastSeenAt(), "HIGH"));
                case "STALE" -> out.add(new Alert("STALE", h.deviceInstanceId(), h.siteCode(), h.lastSeenAt(), "MEDIUM"));
                case "DEGRADED" -> out.add(new Alert("ERROR_RATE", h.deviceInstanceId(), h.siteCode(), now, "MEDIUM"));
                default -> {
                }
            }
            if (h.p95LatencyMillis() != null && h.p95LatencyMillis() > 5000) {
                out.add(new Alert("LATENCY", h.deviceInstanceId(), h.siteCode(), now, "LOW"));
            }
        }
        for (Map<String, Object> r : jdbc.queryForList("""
                select site_code, min(sla_due_at) as since from deviceint.unmatched_item where status = 'OPEN' and sla_due_at < ? group by site_code""",
                Timestamp.from(now))) {
            out.add(new Alert("UNMATCHED_SLA", null, (String) r.get("site_code"), ts(r.get("since")), "MEDIUM"));
        }
        for (Map<String, Object> r : jdbc.queryForList("select endpoint_id, site_code, queue_capacity_pct, last_heartbeat_at from deviceint.gateway_state "
                + "where queue_capacity_pct >= 70")) {
            int pct = ((Number) r.get("queue_capacity_pct")).intValue();
            out.add(new Alert("QUEUE_CAPACITY", null, (String) r.get("site_code"), ts(r.get("last_heartbeat_at")), pct >= 90 ? "HIGH" : "MEDIUM"));
        }
        return out;
    }

    // ------------------------------------------------------------------ archive, tenants, gates

    public RawPayloadStore.Verification verifyArchive(UUID archiveId) {
        RawPayloadStore.Verification v = raw.verify(archiveId);
        audit.record("DEVICE_ARCHIVE_VERIFY", "DEVICE_ARCHIVE", archiveId.toString(), AuditService.Outcome.SUCCESS, v.intact() ? "intact" : "MISMATCH");
        return v;
    }

    public List<Map<String, Object>> tenants() {
        return jdbc.queryForList("""
                select code, messages_per_minute as "messagesPerMinute", max_connectors as "maxConnectors", catalogue_scope as "catalogueScope"
                  from deviceint.tenant_quota order by code""");
    }

    public Map<String, Object> putQuota(String actor, String code, int messagesPerMinute, int maxConnectors, String catalogueScope) {
        if (!code.matches("[A-Z0-9_\\-]{2,40}") || messagesPerMinute < 1 || maxConnectors < 1) {
            throw ApiException.badRequest("validation_failed", "Invalid tenant quota");
        }
        String scope = catalogueScope == null || catalogueScope.isBlank() ? code : catalogueScope;
        int n = jdbc.update("update deviceint.tenant_quota set messages_per_minute = ?, max_connectors = ?, catalogue_scope = ?, updated_by = ?, updated_at = ? where code = ?",
                messagesPerMinute, maxConnectors, scope, actor, Timestamp.from(clock.instant()), code);
        if (n == 0) {
            jdbc.update("insert into deviceint.tenant_quota (code, messages_per_minute, max_connectors, catalogue_scope, updated_by, updated_at) values (?, ?, ?, ?, ?, ?)",
                    code, messagesPerMinute, maxConnectors, scope, actor, Timestamp.from(clock.instant()));
        }
        audit.record("DEVICE_TENANT_QUOTA", "DEVICE_TENANT", code, AuditService.Outcome.SUCCESS, "perMinute=" + messagesPerMinute);
        return Map.of("code", code, "messagesPerMinute", messagesPerMinute, "maxConnectors", maxConnectors, "catalogueScope", scope);
    }

    public List<Map<String, Object>> gates() {
        Map<String, Map<String, Object>> signoff = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList("select gate_code, status, signed_by, signed_at from deviceint.gate_signoff")) {
            signoff.put((String) r.get("gate_code"), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (GateCatalogue.Definition d : GateCatalogue.GATES.values().stream().sorted((a, b) -> a.code().compareTo(b.code())).toList()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("code", d.code());
            g.put("description", d.description());
            g.put("families", d.families());
            g.put("parameters", d.parameters());
            Map<String, Object> s = signoff.getOrDefault(d.code(), Map.of("status", "PENDING"));
            Map<String, Object> so = new LinkedHashMap<>();
            so.put("status", s.get("status"));
            if (s.get("signed_by") != null) {
                so.put("by", s.get("signed_by"));
                so.put("at", ts(s.get("signed_at")));
            }
            g.put("signOff", so);
            out.add(g);
        }
        return out;
    }
}
