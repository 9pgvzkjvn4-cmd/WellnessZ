package com.wellnessz.e05.restriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.FitnessFeed;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e05.E05Events;
import com.wellnessz.e05.decision.DecisionRules;
import com.wellnessz.e05.model.RestrictionInstance;
import com.wellnessz.e05.model.RestrictionInstanceRepository;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Restriction instances (E05-F06): creation from fitness decisions, active / expiring / lapsed list, one-click extend or
 * close, the daily expiry job (T−5 alerts to case owner, manager and employee; lapsed marking), the manager's team view
 * (functional wording only, audited) and the unit dashboard.
 */
@Service
public class RestrictionService {

    static final Set<String> OPEN = Set.of("ACTIVE", "LAPSED");

    private final RestrictionInstanceRepository instances;
    private final OhCaseRepository cases;
    private final CaseAccess access;
    private final EmployeeRecordAccess records;
    private final CaseTimeline timeline;
    private final FitnessSupport support;
    private final EventBus bus;
    private final AuditService audit;

    public RestrictionService(RestrictionInstanceRepository instances, OhCaseRepository cases, CaseAccess access, EmployeeRecordAccess records,
                              CaseTimeline timeline, FitnessSupport support, EventBus bus, AuditService audit) {
        this.instances = instances;
        this.cases = cases;
        this.access = access;
        this.records = records;
        this.timeline = timeline;
        this.support = support;
        this.bus = bus;
        this.audit = audit;
    }

    public record InstanceView(UUID id, String employeeId, String employeeName, UUID caseId, String caseRef, UUID decisionId, String code,
                               String label, BigDecimal value, LocalDate validFrom, LocalDate validUntil, String status, Instant alertedAt,
                               Instant closedAt, String closeReason) {
    }

    public record TeamRestriction(String label, LocalDate validUntil, String status) {
    }

    public record TeamMember(String employeeId, String employeeName, String fitnessStatus, List<TeamRestriction> restrictions) {
    }

    public record UnitCounts(String unit, long active, long expiring, long lapsed) {
    }

    public record Dashboard(List<UnitCounts> byUnit) {
    }

    // ------------------------------------------------------------------ creation (used by decisions)

    /** Creates one instance per catalogue restriction of a decision. */
    @Transactional
    public List<RestrictionInstance> create(OhCase c, UUID decisionId, List<RestrictionRef.View> views, LocalDate validFrom,
                                            LocalDate validUntil, String createdBy) {
        Employee e = support.employee(c.getEmployeeId()).orElse(null);
        Instant now = support.clock().instant();
        List<RestrictionInstance> out = new ArrayList<>();
        for (RestrictionRef.View v : views) {
            RestrictionInstance r = RestrictionInstance.create();
            r.setEmployeeId(c.getEmployeeId());
            r.setUnitCode(e != null ? e.getSubsidiaryCode() : c.getSubsidiaryCode());
            r.setDepartmentCode(e != null ? e.getDepartmentCode() : c.getOrgUnitCode());
            r.setCaseId(c.getId());
            r.setCaseRef(c.getCaseRef());
            r.setDecisionId(decisionId);
            r.setCode(v.code());
            r.setLabel(v.label());
            r.setRestrictionValue(v.value());
            r.setValidFrom(validFrom);
            r.setValidUntil(validUntil);
            r.setStatus("ACTIVE");
            r.setCreatedBy(createdBy);
            r.setCreatedAt(now);
            r.setUpdatedAt(now);
            out.add(instances.save(r));
        }
        return out;
    }

    /** Closes the open restrictions of a case that belong to earlier decisions (superseded by a new decision). */
    @Transactional
    public int closeSuperseded(UUID caseId, UUID newDecisionId, String by) {
        int n = 0;
        Instant now = support.clock().instant();
        for (RestrictionInstance r : instances.findByCaseIdOrderByCreatedAtAsc(caseId)) {
            if (OPEN.contains(r.getStatus()) && !newDecisionId.equals(r.getDecisionId())) {
                close(r, "Superseded by a new fitness decision", by, now);
                n++;
            }
        }
        return n;
    }

    /** Functional restrictions currently in force on a case (for duty proposals and RTW plans). */
    @Transactional(readOnly = true)
    public List<RestrictionRef.View> activeForCase(UUID caseId) {
        return instances.findByCaseIdOrderByCreatedAtAsc(caseId).stream().filter(r -> "ACTIVE".equals(r.getStatus()))
                .map(r -> new RestrictionRef.View(r.getCode(), r.getLabel(), r.getRestrictionValue())).toList();
    }

    @Transactional(readOnly = true)
    public List<RestrictionInstance> forEmployee(String employeeId) {
        return instances.findByEmployeeIdOrderByValidUntilAsc(employeeId);
    }

    // ------------------------------------------------------------------ OH list, extend, close

    @Transactional(readOnly = true)
    public List<InstanceView> active(CurrentUser user, String unit, String status) {
        requireOh(user);
        LocalDate today = support.today();
        Set<String> stored = "CLOSED".equals(status) ? Set.of("CLOSED") : OPEN;
        List<InstanceView> out = new ArrayList<>();
        for (RestrictionInstance r : instances.findByStatusInOrderByValidUntilAsc(stored)) {
            if (unit != null && !unit.equals(r.getUnitCode()) && !unit.equals(r.getDepartmentCode())) {
                continue;
            }
            Employee e = support.employee(r.getEmployeeId()).orElse(null);
            if (e == null || !records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                continue;
            }
            InstanceView v = view(r, today);
            if (status == null || status.equals(v.status())) {
                out.add(v);
            }
        }
        audit.record("RESTRICTIONS_READ", "RESTRICTION", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public InstanceView extend(CurrentUser user, UUID id, LocalDate validUntil, String reason) {
        RestrictionInstance r = load(user, id, "RESTRICTION_EXTEND");
        if ("CLOSED".equals(r.getStatus())) {
            throw ApiException.conflict("restriction_closed", "A closed restriction cannot be extended");
        }
        LocalDate today = support.today();
        if (validUntil == null || validUntil.isBefore(today) || (r.getValidUntil() != null && !validUntil.isAfter(r.getValidUntil()))) {
            throw ApiException.badRequest("invalid_valid_until", "The new end date must be later than the current one and not in the past");
        }
        String why = FitnessSupport.functional("reason", reason, 500);
        if (why == null) {
            throw ApiException.badRequest("reason_required", "A reason is required");
        }
        LocalDate previous = r.getValidUntil();
        r.setValidUntil(validUntil);
        r.setStatus("ACTIVE");
        r.setAlertedAt(null);
        r.setUpdatedAt(support.clock().instant());
        timeline.record(r.getCaseId(), "RESTRICTION_EXTENDED", r.getLabel() + " extended from " + previous + " to " + validUntil + ": " + why);
        audit.record("RESTRICTION_EXTEND", "RESTRICTION", id.toString(), AuditService.Outcome.SUCCESS, String.valueOf(validUntil));
        return view(r, today);
    }

    @Transactional
    public InstanceView close(CurrentUser user, UUID id, String reason) {
        RestrictionInstance r = load(user, id, "RESTRICTION_CLOSE");
        if ("CLOSED".equals(r.getStatus())) {
            throw ApiException.conflict("restriction_closed", "Restriction already closed");
        }
        String why = FitnessSupport.functional("reason", reason, 500);
        if (why == null) {
            throw ApiException.badRequest("reason_required", "A reason is required");
        }
        close(r, why, user.username(), support.clock().instant());
        audit.record("RESTRICTION_CLOSE", "RESTRICTION", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(r, support.today());
    }

    private void close(RestrictionInstance r, String reason, String by, Instant now) {
        r.setStatus("CLOSED");
        r.setClosedAt(now);
        r.setClosedBy(by);
        r.setCloseReason(reason);
        r.setUpdatedAt(now);
        timeline.record(r.getCaseId(), "RESTRICTION_CLOSED", r.getLabel() + " closed: " + reason);
    }

    private RestrictionInstance load(CurrentUser user, UUID id, String auditAction) {
        requireOh(user);
        RestrictionInstance r = instances.findById(id).orElseThrow(() -> ApiException.notFound("Restriction"));
        access.clinical(user, r.getCaseId(), AccessPolicy.Action.UPDATE, auditAction);
        return r;
    }

    private static void requireOh(CurrentUser user) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Occupational Health only");
        }
    }

    // ------------------------------------------------------------------ daily expiry job (E05-BL-016)

    public record ExpiryRun(int alerted, int lapsed) {
    }

    /** Sends T−5 alerts (case owner, manager, employee – content-free) and marks restrictions past their end date LAPSED. */
    @Transactional
    public ExpiryRun processExpiry(LocalDate today) {
        int alerted = 0;
        int lapsed = 0;
        Instant now = support.clock().instant();
        for (RestrictionInstance r : instances.findActiveEndingOnOrBefore(today.plusDays(DecisionRules.EXPIRING_DAYS))) {
            if (r.getValidUntil().isBefore(today)) {
                r.setStatus("LAPSED");
                r.setUpdatedAt(now);
                timeline.record(r.getCaseId(), "RESTRICTION_LAPSED", r.getLabel() + " lapsed on " + r.getValidUntil() + " – review required");
                lapsed++;
                continue;
            }
            if (r.getAlertedAt() != null) {
                continue;
            }
            Map<String, String> params = Map.of("employeeName", support.employeeName(r.getEmployeeId()), "caseRef", r.getCaseRef(),
                    "dueDate", r.getValidUntil().toString());
            String owner = cases.findById(r.getCaseId()).map(OhCase::getOwnerUsername).orElse(null);
            support.notifyStaff(owner, "restriction.expiring", params);
            support.notifyManagerOf(r.getEmployeeId(), "restriction.expiring", params);
            support.notifyEmployee(r.getEmployeeId(), "restriction.expiring", params);
            bus.publish(new E05Events.RestrictionExpiring(r.getId().toString(), r.getEmployeeId(), r.getValidUntil()));
            r.setAlertedAt(now);
            r.setUpdatedAt(now);
            timeline.record(r.getCaseId(), "RESTRICTION_EXPIRING", r.getLabel() + " ends on " + r.getValidUntil() + " – alerts sent");
            alerted++;
        }
        audit.recordAs("system", "SYSTEM", "RESTRICTION_EXPIRY_JOB", "RESTRICTION", null, AuditService.Outcome.SUCCESS,
                "alerted=" + alerted + " lapsed=" + lapsed);
        return new ExpiryRun(alerted, lapsed);
    }

    // ------------------------------------------------------------------ manager / HR team view (E05-BL-017)

    @Transactional(readOnly = true)
    public List<TeamMember> team(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        LocalDate today = support.today();
        Map<String, List<RestrictionInstance>> byEmployee = new TreeMap<>();
        for (RestrictionInstance r : instances.findByStatusInOrderByValidUntilAsc(OPEN)) {
            byEmployee.computeIfAbsent(r.getEmployeeId(), k -> new ArrayList<>()).add(r);
        }
        List<TeamMember> out = new ArrayList<>();
        for (Map.Entry<String, List<RestrictionInstance>> entry : byEmployee.entrySet()) {
            Employee e = support.employee(entry.getKey()).orElse(null);
            if (e == null || !inScope(user, e)) {
                continue;
            }
            List<TeamRestriction> rs = entry.getValue().stream().map(r -> new TeamRestriction(r.getLabel(), r.getValidUntil(),
                    DecisionRules.restrictionStatus(r.getStatus(), r.getValidUntil(), today))).toList();
            out.add(new TeamMember(e.getEmployeeId(), e.getDisplayName(), simpleFitness(e.getEmployeeId()), rs));
            audit.record("TEAM_RESTRICTIONS_READ", "EMPLOYEE", e.getEmployeeId(), AuditService.Outcome.SUCCESS, "status-only");
        }
        audit.record("TEAM_RESTRICTIONS_READ", "TEAM", user.orgScope(), AuditService.Outcome.SUCCESS, "employees=" + out.size());
        return out;
    }

    /** Status-only scope check (manager / HR inside their org scope, or the employee's line manager). */
    public boolean inScope(CurrentUser user, Employee e) {
        if (user.hasRole(Roles.MANAGER) && user.employeeId() != null && user.employeeId().equals(e.getManagerEmployeeId())) {
            return true;
        }
        AccessDecision d = records.decide(user, AccessPolicy.Action.READ, "EMPLOYEE_RECORD", e, DataClassification.CONFIDENTIAL);
        return d.permitted() && d.disclosure().atLeast(DisclosureLevel.STATUS_ONLY);
    }

    /** FIT / FIT_WITH_LIMITATIONS / UNFIT / PENDING from the employee's most recently updated case. */
    public String simpleFitness(String employeeId) {
        return cases.findByEmployeeIdOrderByCreatedAtDesc(employeeId).stream().max(Comparator.comparing(OhCase::getUpdatedAt))
                .map(c -> simple(c.getFitnessStatus())).orElse("PENDING");
    }

    public static String simple(FitnessStatus f) {
        String roster = FitnessFeed.rosterStatus(f);
        return "LIMITED".equals(roster) ? "FIT_WITH_LIMITATIONS" : roster;
    }

    // ------------------------------------------------------------------ dashboard

    @Transactional(readOnly = true)
    public Dashboard dashboard(CurrentUser user) {
        if (!user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO)) {
            throw ApiException.forbidden("Nursing Head or CMO only");
        }
        LocalDate today = support.today();
        Map<String, long[]> counts = new TreeMap<>();
        for (RestrictionInstance r : instances.findByStatusInOrderByValidUntilAsc(OPEN)) {
            String unit = r.getUnitCode() == null ? "UNKNOWN" : r.getUnitCode();
            long[] c = counts.computeIfAbsent(unit, k -> new long[3]);
            switch (DecisionRules.restrictionStatus(r.getStatus(), r.getValidUntil(), today)) {
                case "EXPIRING" -> c[1]++;
                case "LAPSED" -> c[2]++;
                default -> c[0]++;
            }
        }
        List<UnitCounts> rows = new ArrayList<>();
        counts.forEach((unit, c) -> rows.add(new UnitCounts(unit, c[0], c[1], c[2])));
        audit.record("RESTRICTIONS_DASHBOARD", "RESTRICTION", null, AuditService.Outcome.SUCCESS, "units=" + rows.size());
        return new Dashboard(rows);
    }

    // ------------------------------------------------------------------ views

    public InstanceView view(RestrictionInstance r, LocalDate today) {
        return new InstanceView(r.getId(), r.getEmployeeId(), support.employeeName(r.getEmployeeId()), r.getCaseId(), r.getCaseRef(),
                r.getDecisionId(), r.getCode(), r.getLabel(), r.getRestrictionValue(), r.getValidFrom(), r.getValidUntil(),
                DecisionRules.restrictionStatus(r.getStatus(), r.getValidUntil(), today), r.getAlertedAt(), r.getClosedAt(), r.getCloseReason());
    }
}
