package com.wellnessz.e01.reconciliation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e01.rules.IncidentCaseFactory;
import com.wellnessz.e01.rules.IncidentEvaluation;
import com.wellnessz.e01.rules.IncidentEvaluationRepository;
import com.wellnessz.e01.rules.IncidentFacts;
import com.wellnessz.e01.rules.IntakeRuleRepository;
import com.wellnessz.ehsintake.model.EhsIncident;
import com.wellnessz.ehsintake.model.EhsIncidentRepository;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Incident-to-case reconciliation (E01-BL-004), suggestion review (E01-BL-005), multi-casualty view (E01-BL-020)
 * and the intake dashboard (E01-BL-026). Outputs contain no clinical free text.
 */
@Service
public class ReconciliationService {

    static final Set<String> DISMISS_REASONS = Set.of("NOT_WORK_RELATED", "NO_HEALTH_CONSEQUENCE", "DUPLICATE",
            "INSUFFICIENT_DATA", "OTHER");

    private final JdbcTemplate jdbc;
    private final IncidentEvaluationRepository evaluations;
    private final EhsIncidentRepository incidents;
    private final IntakeRuleRepository rules;
    private final IncidentCaseFactory factory;
    private final OhCaseRepository cases;
    private final AuditService audit;
    private final Clock clock;
    private final int minGroup;

    public ReconciliationService(JdbcTemplate jdbc, IncidentEvaluationRepository evaluations, EhsIncidentRepository incidents,
                                 IntakeRuleRepository rules, IncidentCaseFactory factory, OhCaseRepository cases,
                                 AuditService audit, Clock clock,
                                 @Value("${wellnessz.analytics.min-group-size:5}") int minGroup) {
        this.jdbc = jdbc;
        this.evaluations = evaluations;
        this.incidents = incidents;
        this.rules = rules;
        this.factory = factory;
        this.cases = cases;
        this.audit = audit;
        this.clock = clock;
        this.minGroup = minGroup;
    }

    public record Row(String incidentId, String externalRef, Instant occurredAt, String incidentClass, String severity,
                      Boolean lostTime, String unitCode, String location, String outcome, String ruleName, String caseId,
                      String caseRef, String caseStatus, String dismissReason, String decidedBy) {
    }

    private static final String SELECT = """
            select i.id, i.external_ref, i.occurred_at, i.incident_class, i.severity, i.lost_time, i.unit_code, i.location,
                   coalesce(e.outcome, 'PENDING') outcome, e.rule_name, c.id case_id, c.case_ref, c.status case_status,
                   e.dismiss_reason, e.decided_by
              from intake.ehs_incident i
              left join intake.incident_evaluation e on e.incident_id = i.id
              left join casemgmt.oh_case c on c.id = e.case_id
            """;

    private static final RowMapper<Row> MAPPER = (rs, n) -> new Row(rs.getString("id"), rs.getString("external_ref"),
            ts(rs.getTimestamp("occurred_at")), rs.getString("incident_class"), rs.getString("severity"),
            (Boolean) rs.getObject("lost_time"), rs.getString("unit_code"), rs.getString("location"), rs.getString("outcome"),
            rs.getString("rule_name"), rs.getString("case_id"), rs.getString("case_ref"), rs.getString("case_status"),
            rs.getString("dismiss_reason"), rs.getString("decided_by"));

    private static Instant ts(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    @Transactional(readOnly = true)
    public List<Row> reconciliation(LocalDate from, LocalDate to, String outcome, String unit) {
        Instant start = (from == null ? LocalDate.now(clock).minusDays(30) : from).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant end = (to == null ? LocalDate.now(clock) : to).plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        if (ChronoUnit.DAYS.between(start, end) > 400) {
            throw ApiException.badRequest("range_too_large", "Maximum range is 400 days");
        }
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(start), Timestamp.from(end)));
        StringBuilder sql = new StringBuilder(SELECT).append(" where i.received_at >= ? and i.received_at < ?");
        if (outcome != null) {
            sql.append(" and coalesce(e.outcome, 'PENDING') = ?");
            args.add(outcome);
        }
        if (unit != null) {
            sql.append(" and i.unit_code = ?");
            args.add(unit);
        }
        sql.append(" order by i.received_at desc limit 5000");
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    /** Weekly exception report: incidents with injury / lost time that did not produce a case. */
    @Transactional(readOnly = true)
    public List<Row> exceptions(LocalDate from, LocalDate to) {
        return reconciliation(from == null ? LocalDate.now(clock).minusDays(7) : from, to, null, null).stream()
                .filter(r -> Set.of("IGNORED", "NO_MATCH", "DISMISSED", "PENDING").contains(r.outcome()))
                .filter(r -> Boolean.TRUE.equals(r.lostTime())
                        || (r.incidentClass() != null && (r.incidentClass().contains("INJURY") || r.incidentClass().contains("EXPOSURE")
                        || r.incidentClass().contains("ILLNESS"))))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Row> suggestions() {
        return jdbc.query(SELECT + " where e.outcome = 'SUGGESTED' order by e.evaluated_at desc limit 1000", MAPPER);
    }

    public record Accepted(String caseId, String caseRef) {
    }

    @Transactional
    public Accepted accept(CurrentUser user, UUID incidentId, String employeeId) {
        IncidentEvaluation e = evaluations.findById(incidentId)
                .filter(x -> x.getOutcome() == IncidentEvaluation.Outcome.SUGGESTED)
                .orElseThrow(() -> ApiException.notFound("Suggestion"));
        EhsIncident i = incidents.findById(incidentId).orElseThrow(() -> ApiException.notFound("Incident"));
        String emp = employeeId != null ? employeeId : i.getEmployeeId();
        if (emp == null || emp.isBlank()) {
            throw ApiException.badRequest("employee_required", "Identify the employee before accepting the suggestion");
        }
        var rule = e.getRuleId() == null ? null : rules.findById(e.getRuleId()).orElse(null);
        IncidentFacts facts = new IncidentFacts(i.getId().toString(), i.getExternalRef(), emp, i.getEmployeeName(),
                i.getOrgUnitCode(), i.getIncidentType(), i.getSeverity(), i.isInjury(), i.getIncidentClass(), i.getBodyPart(),
                i.getLostTime(), i.getExposureType(), i.getUnitCode(), i.getLocation(), i.getShift(),
                i.getOccurredAt() == null ? null : i.getOccurredAt().toString(), i.getReporterEmail());
        var c = factory.open(facts, emp, rule == null ? null : rule.getCaseType(), rule == null ? null : rule.getUrgency());
        e.caseCreated(c.getId(), IncidentEvaluation.Outcome.ACCEPTED, user.username(), clock.instant());
        audit.record("INTAKE_SUGGESTION_ACCEPT", "EHS_INCIDENT", incidentId.toString(), AuditService.Outcome.SUCCESS, c.getCaseRef());
        return new Accepted(c.getId().toString(), c.getCaseRef());
    }

    @Transactional
    public Row dismiss(CurrentUser user, UUID incidentId, String reason, String note) {
        if (!DISMISS_REASONS.contains(reason)) {
            throw ApiException.badRequest("invalid_reason", "Unknown dismiss reason");
        }
        IncidentEvaluation e = evaluations.findById(incidentId)
                .filter(x -> x.getOutcome() == IncidentEvaluation.Outcome.SUGGESTED)
                .orElseThrow(() -> ApiException.notFound("Suggestion"));
        e.dismiss(reason, note, user.username(), clock.instant());
        audit.record("INTAKE_SUGGESTION_DISMISS", "EHS_INCIDENT", incidentId.toString(), AuditService.Outcome.SUCCESS, reason);
        evaluations.flush();
        return jdbc.query(SELECT + " where i.id = ?", MAPPER, incidentId).getFirst();
    }

    /** Multi-casualty: all cases linked to one incident reference, status only (E01-BL-020). */
    @Transactional(readOnly = true)
    public List<CaseViews.StatusOnly> casesOfIncident(String incidentRefOrId) {
        String ref = incidentRefOrId;
        try {
            UUID id = UUID.fromString(incidentRefOrId);
            ref = incidents.findById(id).map(EhsIncident::getExternalRef).orElse(incidentRefOrId);
        } catch (IllegalArgumentException ignored) {
            // already an external reference
        }
        return cases.findByIncidentRefOrderByCreatedAt(ref).stream().map(CaseViews.StatusOnly::of).toList();
    }

    /** CSV export; cells starting with = + - @ are prefixed to neutralise spreadsheet formula injection. */
    public String toCsv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("incidentId,externalRef,occurredAt,incidentClass,severity,lostTime,unit,location,outcome,rule,caseRef,caseStatus,dismissReason,decidedBy\n");
        for (Row r : rows) {
            sb.append(String.join(",", csv(r.incidentId()), csv(r.externalRef()), csv(r.occurredAt()), csv(r.incidentClass()),
                    csv(r.severity()), csv(r.lostTime()), csv(r.unitCode()), csv(r.location()), csv(r.outcome()), csv(r.ruleName()),
                    csv(r.caseRef()), csv(r.caseStatus()), csv(r.dismissReason()), csv(r.decidedBy()))).append('\n');
        }
        return sb.toString();
    }

    static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    public record CountItem(String source, Long count, boolean suppressed) {
    }

    public record Dashboard(List<CountItem> bySource, List<Map<String, Object>> byUnit, Map<String, Object> slaCompliance,
                            Map<String, Long> backlog, List<Map<String, Object>> trend, int minGroupSize) {
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(LocalDate from, LocalDate to, String subsidiary) {
        Instant start = (from == null ? LocalDate.now(clock).minusDays(30) : from).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant end = (to == null ? LocalDate.now(clock) : to).plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        String sub = subsidiary == null ? "%" : subsidiary;
        List<CountItem> bySource = jdbc.query("""
                select source, count(*) n from casemgmt.oh_case where created_at >= ? and created_at < ?
                   and coalesce(subsidiary_code,'') like ? group by source order by source""",
                (rs, i) -> suppress(rs.getString(1), rs.getLong(2)), Timestamp.from(start), Timestamp.from(end), sub);
        List<Map<String, Object>> byUnit = jdbc.queryForList("""
                select coalesce(c.subsidiary_code,'UNKNOWN') unit,
                       count(*) filter (where c.status not in ('CLOSED','MERGED')) open,
                       count(*) filter (where t.status = 'BREACHED') overdue
                  from casemgmt.oh_case c
                  left join triage.sla_timer t on t.case_id = c.id and t.status = 'BREACHED'
                 where coalesce(c.subsidiary_code,'') like ?
                 group by 1 order by 1""", sub);
        Map<String, Object> sla = jdbc.queryForMap("""
                select count(*) filter (where status = 'MET') met, count(*) filter (where status = 'BREACHED') breached
                  from triage.sla_timer where started_at >= ? and started_at < ?""", Timestamp.from(start), Timestamp.from(end));
        long met = ((Number) sla.get("met")).longValue();
        long breached = ((Number) sla.get("breached")).longValue();
        Map<String, Object> slaOut = Map.of("met", met, "breached", breached,
                "pct", met + breached == 0 ? 100.0 : Math.round(1000.0 * met / (met + breached)) / 10.0);
        Map<String, Long> backlog = Map.of(
                "unassigned", count("select count(*) from casemgmt.oh_case where owner_username is null and status not in ('CLOSED','MERGED')"),
                "overdue", count("select count(distinct case_id) from triage.sla_timer where status = 'BREACHED'"),
                "awaitingTriage", count("select count(*) from casemgmt.oh_case where status = 'INTAKE'"));
        List<Map<String, Object>> trend = jdbc.queryForList("""
                select to_char(date_trunc('day', created_at), 'YYYY-MM-DD') date, count(*) count from casemgmt.oh_case
                 where created_at >= ? and created_at < ? group by 1 order by 1""", Timestamp.from(start), Timestamp.from(end));
        return new Dashboard(bySource, byUnit, slaOut, backlog, trend, minGroup);
    }

    private CountItem suppress(String key, long n) {
        return n > 0 && n < minGroup ? new CountItem(key, null, true) : new CountItem(key, n, false);
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
