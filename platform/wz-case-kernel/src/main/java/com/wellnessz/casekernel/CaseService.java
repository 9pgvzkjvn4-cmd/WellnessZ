package com.wellnessz.casekernel;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseEvents;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.abac.PolicyEngine;
import com.wellnessz.security.abac.ResourceAttributes;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Case kernel service. Every read goes through the ABAC {@link PolicyEngine} and returns the projection matching
 * the granted disclosure level – this is where the "status-only disclosure rule" is enforced for every module.
 */
@Service
public class CaseService {

    public static final String ROSTERING_DESTINATION = "crew-rostering";

    private final OhCaseRepository cases;
    private final OrgUnitRepository orgUnits;
    private final PolicyEngine policy;
    private final AuditService audit;
    private final EventBus bus;
    private final Clock clock;
    private final ModuleRegistry modules;

    public CaseService(OhCaseRepository cases, OrgUnitRepository orgUnits, PolicyEngine policy, AuditService audit,
                       EventBus bus, Clock clock, ModuleRegistry modules) {
        this.modules = modules;
        this.cases = cases;
        this.orgUnits = orgUnits;
        this.policy = policy;
        this.audit = audit;
        this.bus = bus;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Object> list(CurrentUser user, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        List<OhCase> candidates;
        if (user.hasAnyRole(Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.PSYCH_CLINICIAN, Roles.OH_ADMIN)) {
            candidates = cases.findAllByOrderByUpdatedAtDesc(page);
        } else if (user.hasAnyRole(Roles.MANAGER, Roles.HR) && user.orgScope() != null) {
            candidates = cases.findInOrgScope(user.orgScope(), page);
        } else if (user.employeeId() != null) {
            candidates = cases.findByEmployeeIdOrderByCreatedAtDesc(user.employeeId());
        } else {
            candidates = List.of();
        }
        List<Object> visible = candidates.stream()
                .map(c -> project(c, policy.decide(user, AccessPolicy.Action.READ, attributes(c))))
                .flatMap(Optional::stream)
                .toList();
        audit.record("CASE_LIST", "CASE", null, AuditService.Outcome.SUCCESS, "returned=" + visible.size());
        return visible;
    }

    @Transactional(readOnly = true)
    public Object get(CurrentUser user, UUID id) {
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        AccessDecision d = policy.decide(user, AccessPolicy.Action.READ, attributes(c));
        if (!d.permitted()) {
            audit.record("CASE_READ", "CASE", id.toString(), AuditService.Outcome.DENIED, d.reason());
            throw ApiException.notFound("Case"); // 404 rather than 403: do not confirm existence
        }
        audit.record("CASE_READ", "CASE", id.toString(), AuditService.Outcome.SUCCESS, d.disclosure().name());
        return project(c, d).orElseThrow();
    }

    @Transactional(readOnly = true)
    public CaseViews.StatusOnly status(CurrentUser user, UUID id) {
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        AccessDecision d = policy.decide(user, AccessPolicy.Action.READ, attributes(c));
        if (!d.permitted()) {
            audit.record("CASE_STATUS_READ", "CASE", id.toString(), AuditService.Outcome.DENIED, d.reason());
            throw ApiException.notFound("Case");
        }
        audit.record("CASE_STATUS_READ", "CASE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return CaseViews.StatusOnly.of(c);
    }

    @Transactional
    public CaseViews.Clinical selfReferral(CurrentUser user, CaseCategory category, String description) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Self-referral is available to employees only");
        }
        modules.requireEnabled("E13");
        OhCase c = open(user.employeeId(), user.displayName(), user.orgScope() == null ? null : lastSegment(user.orgScope()),
                category, CaseSource.SELF_REFERRAL, null, description);
        audit.record("CASE_SELF_REFERRAL", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, category.name());
        return CaseViews.Clinical.of(c, DisclosureLevel.SELF);
    }

    /** Opens a case from an upstream trigger (EHS incident, system trigger). Idempotent on source + sourceRef. */
    @Transactional
    public OhCase openFromTrigger(String employeeId, String employeeName, String orgUnitCode, CaseCategory category,
                                  CaseSource source, String sourceRef, String summary) {
        return cases.findBySourceAndSourceRef(source, sourceRef)
                .orElseGet(() -> open(employeeId, employeeName, orgUnitCode, category, source, sourceRef, summary));
    }

    @Transactional
    public CaseViews.StatusOnly recordFitness(CurrentUser user, UUID id, FitnessStatus fitness, LocalDate until) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.PSYCH_CLINICIAN)) {
            throw ApiException.forbidden("Only OH clinicians can record fitness outcomes");
        }
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        policy.require(user, AccessPolicy.Action.UPDATE, attributes(c), DisclosureLevel.CLINICAL);
        c.recordFitness(fitness, until, clock.instant());
        bus.publish(new CaseEvents.FitnessStatusChanged(c.getCaseRef(), c.getEmployeeId(), fitness.name(), until,
                LocalDate.now(clock)), ROSTERING_DESTINATION);
        audit.record("FITNESS_RECORDED", "CASE", id.toString(), AuditService.Outcome.SUCCESS, fitness.name());
        return CaseViews.StatusOnly.of(c);
    }

    @Transactional
    public CaseViews.StatusOnly transition(CurrentUser user, UUID id, CaseStatus next) {
        if (!user.hasAnyRole(Roles.OH_CLINICIAN, Roles.CASE_MANAGER, Roles.PSYCH_CLINICIAN)) {
            throw ApiException.forbidden("Not permitted to change case status");
        }
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        policy.require(user, AccessPolicy.Action.UPDATE, attributes(c), DisclosureLevel.CLINICAL);
        try {
            c.transition(next, clock.instant());
        } catch (IllegalStateException e) {
            throw ApiException.conflict("invalid_transition", e.getMessage());
        }
        audit.record("CASE_TRANSITION", "CASE", id.toString(), AuditService.Outcome.SUCCESS, next.name());
        return CaseViews.StatusOnly.of(c);
    }

    private OhCase open(String employeeId, String name, String orgUnitCode, CaseCategory category, CaseSource source,
                        String sourceRef, String summary) {
        String path = orgUnitCode == null ? null : orgUnits.findById(orgUnitCode).map(OrgUnit::getPath).orElse(null);
        String ref = "OH-" + Year.now(clock).getValue() + "-" + String.format("%06d", cases.nextCaseNumber());
        OhCase c = cases.save(new OhCase(ref, employeeId, name, orgUnitCode, path, category, source, sourceRef, summary,
                clock.instant()));
        bus.publish(new CaseEvents.CaseOpened(c.getId().toString(), ref, category.name(), source.name()));
        return c;
    }

    private static Optional<Object> project(OhCase c, AccessDecision d) {
        if (!d.permitted()) {
            return Optional.empty();
        }
        return Optional.of(switch (d.disclosure()) {
            case STATUS_ONLY -> CaseViews.StatusOnly.of(c);
            case SELF, CLINICAL, RESTRICTED_CLINICAL -> CaseViews.Clinical.of(c, d.disclosure());
            case NONE -> throw new IllegalStateException();
        });
    }

    static ResourceAttributes attributes(OhCase c) {
        return new ResourceAttributes("CASE", c.getEmployeeId(), c.getOrgUnitPath(), c.getClassification());
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
