package com.wellnessz.e10.trigger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.SubstanceEnums.Panel;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.TestingPolicy;
import com.wellnessz.e10.policy.PolicyService;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews;
import com.wellnessz.e10.support.TestingViews.ForCauseView;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Post-incident and for-cause triggers (E10-F02): the incident-feed rule table, the supervisor request with observation
 * checklist and second observer, the policy time windows and urgent dispatch to the collection staff; and the requirement
 * work list.
 */
@Service("e10TriggerService")
public class TriggerService {

    private static final Logger log = LoggerFactory.getLogger(TriggerService.class);
    /** Panels used when the employee is not in any testing pool (for-cause requests for other safety-sensitive staff). */
    private static final List<String> DEFAULT_PANELS = List.of(Panel.DRUGS_10.name(), Panel.ALCOHOL_BREATH.name());

    private final E10TestRepository tests;
    private final PolicyService policies;
    private final TestCore core;
    private final SubstanceSupport support;
    private final EmployeeRecordAccess records;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public TriggerService(E10TestRepository tests, PolicyService policies, TestCore core, SubstanceSupport support, EmployeeRecordAccess records,
                          JdbcTemplate jdbc, AuditService audit) {
        this.tests = tests;
        this.policies = policies;
        this.core = core;
        this.support = support;
        this.records = records;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The fields of {@code ehs.incident.received.v1} the post-incident rule needs. */
    public record IncidentFacts(String incidentId, String externalRef, String employeeId, String incidentType, String severity, String incidentClass,
                                String incidentOccurredAt) {

        Instant occurredAt() {
            try {
                return incidentOccurredAt == null ? null : Instant.parse(incidentOccurredAt);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    public List<PostIncidentRules.Rule> rules() {
        return jdbc.query("""
                select id, name, priority, active, incident_class, min_severity, action from substance.post_incident_rule order by priority, id""",
                (rs, i) -> new PostIncidentRules.Rule(rs.getLong(1), rs.getString(2), rs.getInt(3), rs.getBoolean(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)));
    }

    /** Qualifying incident for an employee in a pool → POST_INCIDENT test with alcohol / drug windows, urgent dispatch. */
    @Transactional
    public Optional<DaTest> onIncident(IncidentFacts f) {
        if (f.incidentId() == null || f.employeeId() == null || f.employeeId().isBlank()) {
            return Optional.empty();
        }
        if (!PostIncidentRules.qualifies(rules(), f.incidentClass(), f.severity())) {
            log.info("E10 incident {}: no post-incident test required", f.incidentId());
            return Optional.empty();
        }
        Employee e = support.employee(f.employeeId()).orElse(null);
        Optional<TestingPolicy> policy = policies.policyFor(e);
        if (policy.isEmpty()) {
            log.info("E10 incident {}: employee not in a testing pool", f.incidentId());
            return Optional.empty();
        }
        String sourceRef = "incident:" + f.incidentId();
        if (tests.findBySourceRef(sourceRef).isPresent()) {
            return tests.findBySourceRef(sourceRef);
        }
        TestingPolicy p = policy.get();
        Instant occurred = Optional.ofNullable(f.occurredAt()).orElse(support.now());
        DaTest t = core.create(new TestCore.NewTest(f.employeeId(), p.getRoleGroup(), Reason.POST_INCIDENT.name(),
                SubstanceSupport.split(p.getPanels()), sourceRef, "ehs-incident-feed"));
        TestWindows.Windows w = TestWindows.windows(occurred, p.getAlcoholWindowHours(), p.getDrugWindowHours());
        t.setIncidentRef(f.externalRef() != null ? f.externalRef() : f.incidentId());
        t.setObservedAt(occurred);
        t.setAlcoholDueAt(TestCore.hasAlcohol(t) ? w.alcoholDueAt() : null);
        t.setDrugDueAt(TestCore.hasDrugs(t) ? w.drugDueAt() : null);
        t.setDueAt(TestCore.hasDrugs(t) ? w.drugDueAt() : w.alcoholDueAt());
        if (e.getLocationCode() != null) {
            t.setSiteCode(e.getLocationCode());
        }
        core.dispatch(t, true);
        audit.recordAs("ehs-incident-feed", "MODULE", "DA_POST_INCIDENT_REQUIRED", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS,
                f.incidentClass());
        return Optional.of(t);
    }

    public record ForCauseCommand(String employeeId, Instant observedAt, String location, List<String> observations, String description,
                                  String secondObserverName) {
    }

    /** Supervisor / safety-manager request with the observation checklist and a second observer → requirement + dispatch. */
    @Transactional
    public ForCauseView forCause(CurrentUser user, ForCauseCommand c) {
        Employee e = user.hasRole(Roles.SAFETY_MANAGER)
                ? support.employee(c.employeeId()).orElseThrow(() -> ApiException.notFound("Employee"))
                : records.requireStatus(user, c.employeeId(), "DA_FOR_CAUSE_REQUEST");
        Instant now = support.now();
        if (c.observedAt().isAfter(now.plusSeconds(300))) {
            throw SubstanceSupport.unprocessable("observed_in_future", "observedAt cannot be in the future");
        }
        Optional<TestingPolicy> policy = policies.policyFor(e);
        int alcoholHours = policy.map(TestingPolicy::getAlcoholWindowHours).orElse(8);
        int drugHours = policy.map(TestingPolicy::getDrugWindowHours).orElse(32);
        TestWindows.Windows w = TestWindows.windows(c.observedAt(), alcoholHours, drugHours);
        if (!w.drugDueAt().isAfter(now)) {
            throw SubstanceSupport.unprocessable("window_passed", "The testing window for this observation has passed");
        }
        List<String> panels = policy.map(p -> SubstanceSupport.split(p.getPanels())).orElse(DEFAULT_PANELS);
        DaTest t = core.create(new TestCore.NewTest(e.getEmployeeId(), policy.map(TestingPolicy::getRoleGroup).orElse(null),
                Reason.FOR_CAUSE.name(), panels, "for-cause:" + java.util.UUID.randomUUID(), user.username()));
        t.setObservedAt(c.observedAt());
        t.setLocation(c.location());
        t.setObservations(String.join(",", new LinkedHashSet<>(c.observations())));
        t.setDescription(c.description() == null || c.description().isBlank() ? null : c.description().strip());
        t.setSecondObserverName(c.secondObserverName().strip());
        t.setRequestedBy(user.username());
        t.setAlcoholDueAt(TestCore.hasAlcohol(t) && w.alcoholDueAt().isAfter(now) ? w.alcoholDueAt() : null);
        t.setDrugDueAt(TestCore.hasDrugs(t) ? w.drugDueAt() : null);
        t.setDueAt(w.drugDueAt());
        if (e.getLocationCode() != null) {
            t.setSiteCode(e.getLocationCode());
        }
        core.dispatch(t, true);
        audit.record("DA_FOR_CAUSE_REQUEST", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, "observations=" + t.getObservations());
        return forCauseView(t);
    }

    @Transactional(readOnly = true)
    public List<ForCauseView> myForCause(CurrentUser user) {
        List<ForCauseView> out = tests.findByRequestedByOrderByCreatedAtDesc(user.username()).stream()
                .filter(t -> Reason.FOR_CAUSE.name().equals(t.getReason())).map(this::forCauseView).toList();
        audit.record("DA_FOR_CAUSE_READ", "DA_TEST", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    private ForCauseView forCauseView(DaTest t) {
        return new ForCauseView(t.getId(), t.getRef(), t.getEmployeeId(), support.name(t.getEmployeeId()), TestingViews.publicStatus(t.getStatus()),
                t.getObservedAt(), t.getLocation(), t.getDueAt(), t.getCreatedAt());
    }

    /**
     * Requirement work list: open requirements by default; collectors never see observations or future unannounced follow-up
     * dates, nor tests past collection.
     */
    @Transactional(readOnly = true)
    public List<Requirement> requirements(CurrentUser user, String status) {
        boolean collector = !user.hasAnyRole(Roles.DA_PROGRAMME_MANAGER, Roles.MRO);
        Set<TestStatus> wanted;
        if (status != null) {
            wanted = EnumSet.of(TestStatus.valueOf(status));
        } else {
            wanted = collector ? EnumSet.copyOf(TestStatus.PENDING_COLLECTION) : EnumSet.copyOf(TestStatus.OPEN);
        }
        if (collector) {
            wanted.retainAll(EnumSet.of(TestStatus.REQUIRED, TestStatus.NOTIFIED, TestStatus.DISPATCHED, TestStatus.COLLECTED, TestStatus.IN_LAB));
        }
        java.time.LocalDate today = support.today();
        List<Requirement> out = new ArrayList<>();
        tests.findByStatusInOrderByDueAtAsc(wanted.stream().map(Enum::name).toList()).stream()
                .filter(t -> !(!user.hasRole(Roles.MRO) && t.getScheduledFor() != null && t.getScheduledFor().isAfter(today)))
                .filter(t -> !(collector && TestStatus.REQUIRED.name().equals(t.getStatus()) && t.getScheduledFor() != null))
                .sorted(Comparator.comparing(TestCore::effectiveDue, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(t -> out.add(core.requirement(t, !collector)));
        audit.record("DA_REQUIREMENTS_READ", "DA_TEST", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }
}
