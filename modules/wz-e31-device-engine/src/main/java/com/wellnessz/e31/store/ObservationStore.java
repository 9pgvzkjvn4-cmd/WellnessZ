package com.wellnessz.e31.store;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.secrets.FieldEncryptor;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical observations ({@code deviceint.observation}, clinical content field-encrypted), association candidates and the
 * unmatched queue. Values are immutable once captured: only status, association (manual resolution), routing and
 * verification columns change.
 */
@Component("e31ObservationStore")
public class ObservationStore {

    static final String CANONICAL_CTX = "deviceint.observation.canonical";
    static final String BOUND_CTX = "deviceint.observation.bound";
    static final String SUGGESTIONS_CTX = "deviceint.unmatched.suggestions";
    static final String NOTE_CTX = "deviceint.unmatched.note";
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Suggestion>> SUGGESTIONS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;
    private final JsonMapper json;
    private final Clock clock;

    public ObservationStore(JdbcTemplate jdbc, FieldEncryptor encryptor, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.encryptor = encryptor;
        this.json = json;
        this.clock = clock;
    }

    public record ObservationRow(UUID id, UUID messageId, int recordIndex, UUID reportId, String tenantCode, String siteCode, String employeeId,
                                 UUID caseId, String orderRef, String deviceInstanceId, String equipmentDeviceId, DeviceCodes.Category category,
                                 String status, DeviceCodes.CaptureMethod captureMethod, boolean restricted, String associationMethod,
                                 BigDecimal confidence, UUID sessionId, String formCode, String formVersion, String routeTarget,
                                 boolean verifyBeforePost, String routedRecordType, String routedRecordId, Instant effectiveAt, long clockSkewMs,
                                 Long gatewaySequence, String driverPackageId, String driverVersion, CanonicalDoc canonical,
                                 Map<String, Object> boundFields, boolean testTraffic, String verifiedBy, Instant verifiedAt, String rejectReason,
                                 int routeAttempts, Instant createdAt, Instant updatedAt) {

        public ObservationRow {
            boundFields = boundFields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(boundFields));
        }

        public boolean blocked() {
            return canonical.flags().stream().anyMatch(f -> f.outcome() == DeviceCodes.GateOutcome.BLOCK);
        }

        public ObservationRow withStatus(String newStatus) {
            return new ObservationRow(id, messageId, recordIndex, reportId, tenantCode, siteCode, employeeId, caseId, orderRef, deviceInstanceId,
                    equipmentDeviceId, category, newStatus, captureMethod, restricted, associationMethod, confidence, sessionId, formCode, formVersion,
                    routeTarget, verifyBeforePost, routedRecordType, routedRecordId, effectiveAt, clockSkewMs, gatewaySequence, driverPackageId,
                    driverVersion, canonical, boundFields, testTraffic, verifiedBy, verifiedAt, rejectReason, routeAttempts, createdAt, updatedAt);
        }

        /** The kernel record handed to sinks and capture. */
        public DeviceObservation toKernel() {
            return new DeviceObservation(id, reportId, category, employeeId, caseId, orderRef, effectiveAt, deviceInstanceId, equipmentDeviceId,
                    captureMethod, canonical.components(), canonical.flags(), canonical.provenance(), canonical.attachments(), restricted);
        }
    }

    final RowMapper<ObservationRow> mapper = this::map;

    ObservationRow map(ResultSet rs, int i) throws SQLException {
        String canonical = encryptor.decrypt(rs.getString("canonical"), CANONICAL_CTX);
        String bound = rs.getString("bound_fields") == null ? null : encryptor.decrypt(rs.getString("bound_fields"), BOUND_CTX);
        return new ObservationRow(rs.getObject("id", UUID.class), rs.getObject("message_id", UUID.class), rs.getInt("record_index"),
                rs.getObject("report_id", UUID.class), rs.getString("tenant_code"), rs.getString("site_code"), rs.getString("employee_id"),
                rs.getObject("case_id", UUID.class), rs.getString("order_ref"), rs.getString("device_instance_id"), rs.getString("equipment_device_id"),
                DeviceCodes.Category.valueOf(rs.getString("category")), rs.getString("status"),
                DeviceCodes.CaptureMethod.valueOf(rs.getString("capture_method")), rs.getBoolean("restricted"), rs.getString("association_method"),
                rs.getBigDecimal("confidence"), rs.getObject("session_id", UUID.class), rs.getString("form_code"), rs.getString("form_version"),
                rs.getString("route_target"), rs.getBoolean("verify_before_post"), rs.getString("routed_record_type"), rs.getString("routed_record_id"),
                MessageStore.ts(rs, "effective_at"), rs.getLong("clock_skew_ms"),
                rs.getObject("gateway_sequence") == null ? null : rs.getLong("gateway_sequence"), rs.getString("driver_package_id"),
                rs.getString("driver_version"), json.readValue(canonical, CanonicalDoc.class), bound == null ? Map.of() : json.readValue(bound, MAP),
                rs.getBoolean("test_traffic"), rs.getString("verified_by"), MessageStore.ts(rs, "verified_at"), rs.getString("reject_reason"),
                rs.getInt("route_attempts"), MessageStore.ts(rs, "created_at"), MessageStore.ts(rs, "updated_at"));
    }

    public Optional<ObservationRow> find(UUID id) {
        return jdbc.query("select * from deviceint.observation where id = ?", mapper, id).stream().findFirst();
    }

    public Optional<ObservationRow> lock(UUID id) {
        return jdbc.query("select * from deviceint.observation where id = ? for update", mapper, id).stream().findFirst();
    }

    public List<ObservationRow> forMessage(UUID messageId) {
        return jdbc.query("select * from deviceint.observation where message_id = ? order by record_index", mapper, messageId);
    }

    public List<ObservationRow> forEmployee(String employeeId, String category, Instant from, Instant to, int limit) {
        return jdbc.query("""
                select * from deviceint.observation where employee_id = ? and (?::text is null or category = ?)
                   and (?::timestamptz is null or effective_at >= ?) and (?::timestamptz is null or effective_at < ?) and test_traffic = false
                 order by effective_at desc limit ?""", mapper, employeeId, category, category, MessageStore.ts(from), MessageStore.ts(from),
                MessageStore.ts(to), MessageStore.ts(to), limit);
    }

    public List<ObservationRow> recentForInstance(String deviceInstanceId, int limit) {
        return jdbc.query("select * from deviceint.observation where device_instance_id = ? order by created_at desc limit ?", mapper,
                deviceInstanceId, limit);
    }

    public List<ObservationRow> routePending(Instant updatedBefore, int limit) {
        return jdbc.query("select * from deviceint.observation where status = 'ROUTE_PENDING' and updated_at < ? order by updated_at limit ?",
                mapper, MessageStore.ts(updatedBefore), limit);
    }

    /** Latest routed observation of the same employee and category (out-of-sequence detection). */
    public Optional<ObservationRow> latestRouted(String employeeId, DeviceCodes.Category category) {
        return jdbc.query("""
                select * from deviceint.observation where employee_id = ? and category = ? and status = 'ROUTED' and test_traffic = false
                 order by effective_at desc limit 1""", mapper, employeeId, category.name()).stream().findFirst();
    }

    /** Component values of the employee's last routed observations (for the outlier rule). */
    public List<BigDecimal> history(String employeeId, String category, String key, int limit) {
        List<BigDecimal> out = new ArrayList<>();
        for (ObservationRow r : jdbc.query("""
                select * from deviceint.observation where employee_id = ? and category = ? and status = 'ROUTED' and test_traffic = false
                 order by effective_at desc limit ?""", mapper, employeeId, category, limit)) {
            r.canonical().components().stream().filter(c -> c.key().equals(key) && c.value() != null).findFirst()
                    .ifPresent(c -> out.add(c.value()));
        }
        return out;
    }

    public void upsert(ObservationRow o) {
        Instant now = clock.instant();
        String canonical = encryptor.encrypt(json.writeValueAsString(o.canonical()), CANONICAL_CTX);
        String bound = encryptor.encrypt(json.writeValueAsString(o.boundFields()), BOUND_CTX);
        String flags = flagsSummary(o.canonical().flags());
        int updated = jdbc.update("""
                update deviceint.observation set report_id = ?, site_code = ?, employee_id = ?, case_id = ?, order_ref = ?, device_instance_id = ?,
                    equipment_device_id = ?, category = ?, status = ?, capture_method = ?, restricted = ?, association_method = ?, confidence = ?,
                    session_id = ?, form_code = ?, form_version = ?, route_target = ?, verify_before_post = ?, routed_record_type = ?,
                    routed_record_id = ?, effective_at = ?, clock_skew_ms = ?, gateway_sequence = ?, driver_package_id = ?, driver_version = ?,
                    canonical = ?, bound_fields = ?, flags_summary = ?, verified_by = ?, verified_at = ?, reject_reason = ?, route_attempts = ?,
                    updated_at = ?, version = version + 1 where id = ?""",
                o.reportId(), o.siteCode(), o.employeeId(), o.caseId(), o.orderRef(), o.deviceInstanceId(), o.equipmentDeviceId(),
                o.category().name(), o.status(), o.captureMethod().name(), o.restricted(), o.associationMethod(), o.confidence(), o.sessionId(),
                o.formCode(), o.formVersion(), o.routeTarget(), o.verifyBeforePost(), o.routedRecordType(), o.routedRecordId(),
                MessageStore.ts(o.effectiveAt()), o.clockSkewMs(), o.gatewaySequence(), o.driverPackageId(), o.driverVersion(), canonical, bound,
                flags, o.verifiedBy(), MessageStore.ts(o.verifiedAt()), o.rejectReason(), o.routeAttempts(), Timestamp.from(now), o.id());
        if (updated == 0) {
            jdbc.update("""
                    insert into deviceint.observation (id, message_id, record_index, report_id, tenant_code, site_code, employee_id, case_id, order_ref,
                        device_instance_id, equipment_device_id, category, status, capture_method, restricted, association_method, confidence,
                        session_id, form_code, form_version, route_target, verify_before_post, routed_record_type, routed_record_id, effective_at,
                        clock_skew_ms, gateway_sequence, driver_package_id, driver_version, canonical, bound_fields, flags_summary, test_traffic,
                        verified_by, verified_at, reject_reason, route_attempts, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    o.id(), o.messageId(), o.recordIndex(), o.reportId(), o.tenantCode(), o.siteCode(), o.employeeId(), o.caseId(), o.orderRef(),
                    o.deviceInstanceId(), o.equipmentDeviceId(), o.category().name(), o.status(), o.captureMethod().name(), o.restricted(),
                    o.associationMethod(), o.confidence(), o.sessionId(), o.formCode(), o.formVersion(), o.routeTarget(), o.verifyBeforePost(),
                    o.routedRecordType(), o.routedRecordId(), MessageStore.ts(o.effectiveAt()), o.clockSkewMs(), o.gatewaySequence(),
                    o.driverPackageId(), o.driverVersion(), canonical, bound, flags, o.testTraffic(), o.verifiedBy(), MessageStore.ts(o.verifiedAt()),
                    o.rejectReason(), o.routeAttempts(), Timestamp.from(now), Timestamp.from(now));
        }
    }

    static String flagsSummary(List<DeviceObservation.QualityFlag> flags) {
        StringBuilder sb = new StringBuilder();
        for (DeviceObservation.QualityFlag f : flags) {
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(f.gate()).append(':').append(f.outcome()).append(':').append(f.code());
        }
        return sb.length() > 1000 ? sb.substring(0, 1000) : sb.toString();
    }

    // ------------------------------------------------------------------ association candidates

    public void candidate(UUID observationId, String employeeId, String method, BigDecimal confidence, boolean chosen) {
        jdbc.update("""
                insert into deviceint.association_candidate (id, observation_id, employee_id, method, confidence, chosen, created_at)
                values (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), observationId, employeeId, method, confidence, chosen,
                Timestamp.from(clock.instant()));
    }

    // ------------------------------------------------------------------ unmatched queue

    public record Suggestion(String employeeId, String displayName, String method, BigDecimal confidence) {
    }

    public record UnmatchedRow(UUID id, UUID observationId, String tenantCode, String siteCode, DeviceCodes.Category category, boolean restricted,
                               String deviceInstanceId, Instant receivedAt, Instant slaDueAt, String reasonCode, List<Suggestion> suggestions,
                               String status, String resolvedBy, Instant resolvedAt, String resolutionCode) {
    }

    final RowMapper<UnmatchedRow> unmatchedMapper = this::mapUnmatched;

    UnmatchedRow mapUnmatched(ResultSet rs, int i) throws SQLException {
        String s = rs.getString("suggestions");
        List<Suggestion> sug = s == null ? List.of() : json.readValue(encryptor.decrypt(s, SUGGESTIONS_CTX), SUGGESTIONS);
        return new UnmatchedRow(rs.getObject("id", UUID.class), rs.getObject("observation_id", UUID.class), rs.getString("tenant_code"),
                rs.getString("site_code"), DeviceCodes.Category.valueOf(rs.getString("category")), rs.getBoolean("restricted"),
                rs.getString("device_instance_id"), MessageStore.ts(rs, "received_at"), MessageStore.ts(rs, "sla_due_at"), rs.getString("reason_code"),
                sug, rs.getString("status"), rs.getString("resolved_by"), MessageStore.ts(rs, "resolved_at"), rs.getString("resolution_code"));
    }

    public void openUnmatched(ObservationRow o, String reason, List<Suggestion> suggestions, Instant slaDue) {
        String enc = encryptor.encrypt(json.writeValueAsString(suggestions), SUGGESTIONS_CTX);
        int n = jdbc.update("""
                update deviceint.unmatched_item set status = 'OPEN', reason_code = ?, suggestions = ?, resolved_by = null, resolved_at = null,
                    resolution_code = null, version = version + 1 where observation_id = ?""", reason, enc, o.id());
        if (n == 0) {
            jdbc.update("""
                    insert into deviceint.unmatched_item (id, observation_id, tenant_code, site_code, category, restricted, device_instance_id,
                        received_at, sla_due_at, reason_code, suggestions, status) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN')""",
                    UUID.randomUUID(), o.id(), o.tenantCode(), o.siteCode(), o.category().name(), o.restricted(), o.deviceInstanceId(),
                    Timestamp.from(clock.instant()), Timestamp.from(slaDue), reason, enc);
        }
    }

    public Optional<UnmatchedRow> unmatched(UUID id) {
        return jdbc.query("select * from deviceint.unmatched_item where id = ?", unmatchedMapper, id).stream().findFirst();
    }

    public List<UnmatchedRow> unmatched(String siteCode, String status, String tenant, int limit) {
        return jdbc.query("""
                select * from deviceint.unmatched_item where (?::text is null or site_code = ?) and status = ? and tenant_code = ?
                 order by received_at limit ?""", unmatchedMapper, siteCode, siteCode, status, tenant, limit);
    }

    public long[] unmatchedCount(String siteCode) {
        Instant now = clock.instant();
        Long open = jdbc.queryForObject("select count(*) from deviceint.unmatched_item where status = 'OPEN' and (?::text is null or site_code = ?)",
                Long.class, siteCode, siteCode);
        Long overdue = jdbc.queryForObject("""
                select count(*) from deviceint.unmatched_item where status = 'OPEN' and sla_due_at < ? and (?::text is null or site_code = ?)""",
                Long.class, Timestamp.from(now), siteCode, siteCode);
        return new long[] {open == null ? 0 : open, overdue == null ? 0 : overdue};
    }

    public void resolveUnmatched(UUID id, String status, String actor, String resolutionCode, String note) {
        jdbc.update("""
                update deviceint.unmatched_item set status = ?, resolved_by = ?, resolved_at = ?, resolution_code = ?, note = ?, version = version + 1
                 where id = ?""", status, actor, Timestamp.from(clock.instant()), resolutionCode,
                note == null ? null : encryptor.encrypt(note, NOTE_CTX), id);
    }

    public void closeUnmatchedForObservation(UUID observationId, String actor) {
        jdbc.update("""
                update deviceint.unmatched_item set status = 'RESOLVED', resolved_by = ?, resolved_at = ?, resolution_code = coalesce(resolution_code, 'AUTO')
                 where observation_id = ? and status = 'OPEN'""", actor, Timestamp.from(clock.instant()), observationId);
    }
}
