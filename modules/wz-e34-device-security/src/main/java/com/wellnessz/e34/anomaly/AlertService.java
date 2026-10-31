package com.wellnessz.e34.anomaly;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository.AnomalyRule;
import com.wellnessz.e34.events.E34Events;
import com.wellnessz.e34.identity.EndpointRepository;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.SecurityNotifications;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.audit.AuditService;

/**
 * Security alerts raised by the anomaly rules (contract §15.8): one open alert per rule and subject (repeated detections
 * increase its occurrence count), content-free notification to Security Operations, ITSM ticket for HIGH / CRITICAL, the
 * isolation deadline of the playbook, acknowledgement and closure by SECURITY_OPS.
 */
@Service
public class AlertService {

    static final String COLUMNS = "id, rule_code, severity, endpoint_id, subject_key, site_code, status, detected_at, last_seen_at, occurrences, "
            + "auto_action, isolation_due_at, isolated_at, ticket_ref, acknowledged_by, acknowledged_at, closed_by, closed_at, resolution";

    public record AlertView(UUID id, String ruleCode, String severity, String endpointId, String subjectKey, String siteCode, String status,
                            Instant detectedAt, Instant lastSeenAt, int occurrences, String autoAction, Instant isolationDueAt,
                            Instant isolatedAt, String ticketRef, String acknowledgedBy, Instant acknowledgedAt, String closedBy,
                            Instant closedAt, String resolution) {
    }

    /** @param created a new alert (notifications sent) rather than another occurrence of an open one */
    public record Raised(UUID alertId, boolean created, Instant detectedAt, int occurrences) {
    }

    public record DueIsolation(UUID alertId, String endpointId, String ruleCode, Instant detectedAt) {
    }

    private final JdbcTemplate jdbc;
    private final EventBus bus;
    private final SecurityNotifications notify;
    private final AuditService audit;
    private final E34Settings settings;

    public AlertService(JdbcTemplate jdbc, EventBus bus, SecurityNotifications notify, AuditService audit, E34Settings settings) {
        this.jdbc = jdbc;
        this.bus = bus;
        this.notify = notify;
        this.audit = audit;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Raised raise(AnomalyRule rule, String subjectKey, String endpointId, String siteCode, long eventId) {
        Instant now = settings.now();
        String subject = subjectKey.length() > 200 ? subjectKey.substring(0, 200) : subjectKey;
        List<Raised> open = jdbc.query("select id, detected_at, occurrences from devicesec.alert where rule_code = ? and subject_key = ? "
                + "and status <> 'CLOSED' order by detected_at limit 1 for update", (rs, i) -> new Raised(rs.getObject("id", UUID.class), false,
                EndpointRepository.inst(rs, "detected_at"), rs.getInt("occurrences")), rule.code(), subject);
        if (!open.isEmpty()) {
            Raised r = open.get(0);
            jdbc.update("update devicesec.alert set occurrences = occurrences + 1, last_seen_at = ?, last_event_id = ?, version = version + 1 "
                    + "where id = ?", EndpointRepository.ts(now), eventId, r.alertId());
            return new Raised(r.alertId(), false, r.detectedAt(), r.occurrences() + 1);
        }
        UUID id = UUID.randomUUID();
        boolean high = "HIGH".equals(rule.severity()) || "CRITICAL".equals(rule.severity());
        String ticket = high ? "DSA-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT) : null;
        jdbc.update("insert into devicesec.alert (id, rule_code, severity, endpoint_id, subject_key, site_code, status, detected_at, last_seen_at, "
                + "occurrences, first_event_id, last_event_id, auto_action, ticket_ref) values (?,?,?,?,?,?,'OPEN',?,?,1,?,?,?,?)", id, rule.code(),
                rule.severity(), endpointId, subject, siteCode, EndpointRepository.ts(now), EndpointRepository.ts(now), eventId, eventId,
                rule.action(), ticket);
        if (high) {
            bus.publish(new E34Events.ItsmTicket(UUID.randomUUID().toString(), ticket, null, siteCode, rule.code(), rule.severity(), now),
                    E34Events.ITSM_DESTINATION);
        }
        // NOTIFY rules (certificate expiry) send their own specific template from the job that detects them
        if (!"LOW".equals(rule.severity()) && !"INFO".equals(rule.severity()) && !"NOTIFY".equals(rule.action())) {
            notify.alert(rule.severity(), endpointId != null ? endpointId : rule.code(), siteCode);
        }
        return new Raised(id, true, now, 1);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void setIsolationDue(UUID alertId, Instant due) {
        jdbc.update("update devicesec.alert set isolation_due_at = ? where id = ? and isolation_due_at is null", EndpointRepository.ts(due), alertId);
    }

    /** Open alerts of the endpoint waiting for isolation are marked isolated (drill record). */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> markIsolated(String endpointId, Instant at) {
        List<UUID> ids = jdbc.queryForList("select id from devicesec.alert where endpoint_id = ? and isolation_due_at is not null "
                + "and isolated_at is null", UUID.class, endpointId);
        jdbc.update("update devicesec.alert set isolated_at = ? where endpoint_id = ? and isolation_due_at is not null and isolated_at is null",
                EndpointRepository.ts(at), endpointId);
        return ids;
    }

    @Transactional(readOnly = true)
    public List<DueIsolation> dueIsolations(int limit) {
        return jdbc.query("select id, endpoint_id, rule_code, detected_at from devicesec.alert where isolation_due_at is not null "
                + "and isolated_at is null and status <> 'CLOSED' and endpoint_id is not null order by isolation_due_at limit ?",
                (rs, i) -> new DueIsolation(rs.getObject("id", UUID.class), rs.getString("endpoint_id"), rs.getString("rule_code"),
                        EndpointRepository.inst(rs, "detected_at")), limit);
    }

    @Transactional(readOnly = true)
    public List<AlertView> list(String status, String endpointId, int limit) {
        return jdbc.query("select " + COLUMNS + " from devicesec.alert where (?::varchar is null or status = ?) "
                + "and (?::varchar is null or endpoint_id = ?) order by detected_at desc limit ?", AlertService::map, status, status, endpointId,
                endpointId, Math.min(Math.max(limit, 1), 500));
    }

    @Transactional(readOnly = true)
    public Optional<AlertView> get(UUID id) {
        return jdbc.query("select " + COLUMNS + " from devicesec.alert where id = ?", AlertService::map, id).stream().findFirst();
    }

    @Transactional
    public AlertView acknowledge(UUID id, String actor) {
        settings.requireEnabled();
        AlertView a = get(id).orElseThrow(() -> ApiException.notFound("Alert"));
        if (!"OPEN".equals(a.status())) {
            throw ApiException.conflict("alert_not_open", "Only open alerts can be acknowledged");
        }
        jdbc.update("update devicesec.alert set status = 'ACKNOWLEDGED', acknowledged_by = ?, acknowledged_at = ?, version = version + 1 where id = ?",
                actor, EndpointRepository.ts(settings.now()), id);
        audit.record("DEVICE_ALERT_ACKNOWLEDGE", "DEVICE_SECURITY_ALERT", id.toString(), AuditService.Outcome.SUCCESS, a.ruleCode());
        return get(id).orElseThrow();
    }

    @Transactional
    public AlertView close(UUID id, String actor, String resolution) {
        settings.requireEnabled();
        AlertView a = get(id).orElseThrow(() -> ApiException.notFound("Alert"));
        if ("CLOSED".equals(a.status())) {
            throw ApiException.conflict("alert_closed", "The alert is already closed");
        }
        jdbc.update("update devicesec.alert set status = 'CLOSED', closed_by = ?, closed_at = ?, resolution = ?, version = version + 1 where id = ?",
                actor, EndpointRepository.ts(settings.now()), resolution, id);
        audit.record("DEVICE_ALERT_CLOSE", "DEVICE_SECURITY_ALERT", id.toString(), AuditService.Outcome.SUCCESS, a.ruleCode());
        return get(id).orElseThrow();
    }

    static AlertView map(ResultSet rs, int i) throws SQLException {
        return new AlertView(rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("severity"), rs.getString("endpoint_id"),
                rs.getString("subject_key"), rs.getString("site_code"), rs.getString("status"), EndpointRepository.inst(rs, "detected_at"),
                EndpointRepository.inst(rs, "last_seen_at"), rs.getInt("occurrences"), rs.getString("auto_action"),
                EndpointRepository.inst(rs, "isolation_due_at"), EndpointRepository.inst(rs, "isolated_at"), rs.getString("ticket_ref"),
                rs.getString("acknowledged_by"), EndpointRepository.inst(rs, "acknowledged_at"), rs.getString("closed_by"),
                EndpointRepository.inst(rs, "closed_at"), rs.getString("resolution"));
    }
}
