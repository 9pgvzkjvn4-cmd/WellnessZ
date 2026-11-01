package com.wellnessz.e31.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Work table of the pipeline ({@code deviceint.message}), stage log and dead letters (contract section 8). */
@Component("e31MessageStore")
public class MessageStore {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public MessageStore(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    /**
     * @param headers PHI-free transport metadata
     * @param status  RECEIVED | PROCESSING | RETRY | PROCESSED | DEAD_LETTER | PARKED | DISCARDED
     */
    public record MessageRow(UUID id, String tenantCode, String endpointId, String siteCode, String deviceInstanceId, String kind,
                             String contentType, String idempotencyKey, Long gatewaySequence, Instant capturedAt, Instant edgeClock,
                             Instant receivedAt, UUID rawObjectId, String sha256, Map<String, String> headers, String stage, String status,
                             int attempts, Instant nextAttemptAt, String errorCode, String driverPackageId, String driverVersion, boolean replayed,
                             boolean testTraffic, boolean routeTestForm) {

        public MessageRow {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }
    }

    final RowMapper<MessageRow> mapper = this::map;

    MessageRow map(ResultSet rs, int i) throws SQLException {
        String h = rs.getString("headers");
        Long seq = rs.getObject("gateway_sequence") == null ? null : rs.getLong("gateway_sequence");
        return new MessageRow(rs.getObject("id", UUID.class), rs.getString("tenant_code"), rs.getString("endpoint_id"), rs.getString("site_code"),
                rs.getString("device_instance_id"), rs.getString("kind"), rs.getString("content_type"), rs.getString("idempotency_key"), seq,
                ts(rs, "captured_at"), ts(rs, "edge_clock"), ts(rs, "received_at"), rs.getObject("raw_object_id", UUID.class), rs.getString("sha256"),
                h == null ? Map.of() : json.readValue(h, HEADERS), rs.getString("stage"), rs.getString("status"), rs.getInt("attempts"),
                ts(rs, "next_attempt_at"), rs.getString("error_code"), rs.getString("driver_package_id"), rs.getString("driver_version"),
                rs.getBoolean("replayed"), rs.getBoolean("test_traffic"), rs.getBoolean("route_test_form"));
    }

    static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    public void insert(MessageRow m) {
        jdbc.update("""
                insert into deviceint.message (id, tenant_code, endpoint_id, site_code, device_instance_id, kind, content_type, idempotency_key,
                    gateway_sequence, captured_at, edge_clock, received_at, raw_object_id, sha256, headers, stage, status, attempts, next_attempt_at,
                    driver_package_id, driver_version, replayed, test_traffic, route_test_form)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                m.id(), m.tenantCode(), m.endpointId(), m.siteCode(), m.deviceInstanceId(), m.kind(), m.contentType(), m.idempotencyKey(),
                m.gatewaySequence(), ts(m.capturedAt()), ts(m.edgeClock()), ts(m.receivedAt()), m.rawObjectId(), m.sha256(),
                json.writeValueAsString(m.headers()), m.stage(), m.status(), m.attempts(), ts(m.nextAttemptAt()), m.driverPackageId(),
                m.driverVersion(), m.replayed(), m.testTraffic(), m.routeTestForm());
    }

    public Optional<MessageRow> find(UUID id) {
        return jdbc.query("select * from deviceint.message where id = ?", mapper, id).stream().findFirst();
    }

    public Optional<MessageRow> findByKey(String endpointId, String idempotencyKey) {
        return jdbc.query("select * from deviceint.message where endpoint_id = ? and idempotency_key = ?", mapper, endpointId, idempotencyKey)
                .stream().findFirst();
    }

    /** Row lock for a processing attempt; empty when another worker holds it. */
    public Optional<MessageRow> lock(UUID id) {
        return jdbc.query("select * from deviceint.message where id = ? for update skip locked", mapper, id).stream().findFirst();
    }

    public List<UUID> due(int limit) {
        return jdbc.queryForList("""
                select id from deviceint.message where (status = 'RETRY' and next_attempt_at <= ?)
                    or (status in ('RECEIVED','PROCESSING') and received_at < ?) order by received_at limit ?""", UUID.class,
                ts(clock.instant()), ts(clock.instant().minusSeconds(60)), limit);
    }

    public void update(UUID id, String stage, String status, int attempts, Instant nextAttemptAt, String errorCode, String driverPackageId,
                       String driverVersion) {
        jdbc.update("""
                update deviceint.message set stage = ?, status = ?, attempts = ?, next_attempt_at = ?, error_code = ?,
                    driver_package_id = coalesce(?, driver_package_id), driver_version = coalesce(?, driver_version),
                    processed_at = case when ? in ('PROCESSED','DEAD_LETTER') then ? else processed_at end where id = ?""",
                stage, status, attempts, ts(nextAttemptAt), errorCode, driverPackageId, driverVersion, status, ts(clock.instant()), id);
    }

    /** Prepares a replay: new processing attempt of the same message from the stored raw payload. */
    public void resetForReplay(UUID id, String driverVersion) {
        jdbc.update("""
                update deviceint.message set status = 'RECEIVED', stage = 'PARSE', attempts = 0, next_attempt_at = null, error_code = null,
                    replayed = true, driver_version = coalesce(?, driver_version) where id = ?""", driverVersion, id);
    }

    public void stage(UUID messageId, UUID observationId, String stage, String outcome, String code, long durationMs) {
        jdbc.update("insert into deviceint.stage_log (id, message_id, observation_id, stage, outcome, code, duration_ms, at) values (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), messageId, observationId, stage, outcome, code, durationMs, ts(clock.instant()));
    }

    public record StageEntry(UUID messageId, UUID observationId, String stage, String outcome, String code, Long durationMs, Instant at) {
    }

    public List<StageEntry> stages(UUID messageId) {
        return jdbc.query("select * from deviceint.stage_log where message_id = ? order by at, stage", (rs, i) -> new StageEntry(
                rs.getObject("message_id", UUID.class), rs.getObject("observation_id", UUID.class), rs.getString("stage"), rs.getString("outcome"),
                rs.getString("code"), rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"), ts(rs, "at")), messageId);
    }

    public List<MessageRow> recentForInstance(String deviceInstanceId, int limit) {
        return jdbc.query("select * from deviceint.message where device_instance_id = ? order by received_at desc limit ?", mapper, deviceInstanceId,
                limit);
    }

    public List<MessageRow> between(Instant from, Instant to, String deviceInstanceId, String tenant) {
        return jdbc.query("""
                select * from deviceint.message where received_at >= ? and received_at < ? and status <> 'DISCARDED'
                    and (?::text is null or device_instance_id = ?) and tenant_code = ? and test_traffic = false order by received_at, gateway_sequence""",
                mapper, ts(from), ts(to), deviceInstanceId, deviceInstanceId, tenant);
    }

    public int countForTenantSince(String tenant, Instant since) {
        Integer n = jdbc.queryForObject("select count(*) from deviceint.message where tenant_code = ? and received_at >= ?", Integer.class, tenant,
                ts(since));
        return n == null ? 0 : n;
    }

    public int countForEndpointSince(String endpointId, Instant since) {
        Integer n = jdbc.queryForObject("select count(*) from deviceint.message where endpoint_id = ? and received_at >= ?", Integer.class,
                endpointId, ts(since));
        return n == null ? 0 : n;
    }

    public void park(String endpointId, String code) {
        jdbc.update("update deviceint.message set status = 'PARKED', error_code = ? where endpoint_id = ? and status in ('RECEIVED','RETRY','PROCESSING')",
                code, endpointId);
    }

    /** Parks the in-flight messages of one device instance (its authorisation was withdrawn on a shared endpoint). */
    public void parkInstance(String deviceInstanceId, String code) {
        jdbc.update("update deviceint.message set status = 'PARKED', error_code = ? where device_instance_id = ? and status in ('RECEIVED','RETRY','PROCESSING')",
                code, deviceInstanceId);
    }

    public int unpark(String endpointId) {
        return jdbc.update("""
                update deviceint.message set status = 'RETRY', next_attempt_at = ?, error_code = null
                 where endpoint_id = ? and status = 'PARKED' and error_code = 'ENDPOINT_ISOLATED'""", ts(clock.instant()), endpointId);
    }

    /** A PDF export waiting for its data file (same endpoint and file stem). */
    public Optional<MessageRow> pendingAttachment(String endpointId, String stemHash) {
        return jdbc.query("""
                select * from deviceint.message where endpoint_id = ? and status = 'PARKED' and error_code = 'awaiting_pair'
                   and headers::jsonb ->> 'fileStemHash' = ? order by received_at desc limit 1""", mapper, endpointId, stemHash).stream().findFirst();
    }

    /** The data file a late PDF export belongs to. */
    public Optional<MessageRow> dataFileByStem(String endpointId, String stemHash) {
        return jdbc.query("""
                select * from deviceint.message where endpoint_id = ? and kind = 'FILE' and status <> 'PARKED'
                   and headers::jsonb ->> 'fileStemHash' = ? and coalesce(headers::jsonb ->> 'fileFormat', '') <> 'PDF'
                 order by received_at desc limit 1""", mapper, endpointId, stemHash).stream().findFirst();
    }

    public List<MessageRow> unpairedOlderThan(Instant before) {
        return jdbc.query("select * from deviceint.message where status = 'PARKED' and error_code = 'awaiting_pair' and received_at < ?", mapper,
                ts(before));
    }

    // ------------------------------------------------------------------ dead letters

    public record DeadLetter(UUID id, String tenantCode, UUID messageId, UUID observationId, String deviceInstanceId, String stage, String code,
                             String detailMasked, int attempts, Instant firstFailedAt, Instant lastFailedAt, String status, String resolvedBy,
                             Instant resolvedAt, String reason) {
    }

    final RowMapper<DeadLetter> dlMapper = (rs, i) -> new DeadLetter(rs.getObject("id", UUID.class), rs.getString("tenant_code"),
            rs.getObject("message_id", UUID.class), rs.getObject("observation_id", UUID.class), rs.getString("device_instance_id"), rs.getString("stage"),
            rs.getString("code"), rs.getString("detail_masked"), rs.getInt("attempts"), ts(rs, "first_failed_at"), ts(rs, "last_failed_at"),
            rs.getString("status"), rs.getString("resolved_by"), ts(rs, "resolved_at"), rs.getString("reason"));

    public UUID deadLetter(MessageRow m, UUID observationId, String stage, String code, String detail, int attempts) {
        Optional<DeadLetter> open = jdbc.query("select * from deviceint.dead_letter where message_id = ? and status = 'OPEN' and "
                + "coalesce(observation_id::text, '') = coalesce(?::text, '')", dlMapper, m.id(), observationId).stream().findFirst();
        Instant now = clock.instant();
        if (open.isPresent()) {
            jdbc.update("update deviceint.dead_letter set code = ?, stage = ?, detail_masked = ?, attempts = ?, last_failed_at = ? where id = ?",
                    code, stage, detail, attempts, ts(now), open.get().id());
            return open.get().id();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into deviceint.dead_letter (id, tenant_code, message_id, observation_id, device_instance_id, stage, code, detail_masked, attempts,
                    first_failed_at, last_failed_at, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')""",
                id, m.tenantCode(), m.id(), observationId, m.deviceInstanceId(), stage, code, detail, attempts, ts(now), ts(now));
        return id;
    }

    public Optional<DeadLetter> deadLetter(UUID id) {
        return jdbc.query("select * from deviceint.dead_letter where id = ?", dlMapper, id).stream().findFirst();
    }

    public List<DeadLetter> deadLetters(String stage, String status, int limit) {
        return jdbc.query("""
                select * from deviceint.dead_letter where (?::text is null or stage = ?) and (?::text is null or status = ?)
                 order by last_failed_at desc limit ?""", dlMapper, stage, stage, status, status, limit);
    }

    public void resolveDeadLetter(UUID id, String status, String actor, String reason) {
        jdbc.update("update deviceint.dead_letter set status = ?, resolved_by = ?, resolved_at = ?, reason = ? where id = ?", status, actor,
                ts(clock.instant()), reason, id);
    }

    public void resolveOpenDeadLetters(UUID messageId, String status, String actor, String reason) {
        jdbc.update("update deviceint.dead_letter set status = ?, resolved_by = ?, resolved_at = ?, reason = ? where message_id = ? and status = 'OPEN'",
                status, actor, ts(clock.instant()), reason, messageId);
    }
}
