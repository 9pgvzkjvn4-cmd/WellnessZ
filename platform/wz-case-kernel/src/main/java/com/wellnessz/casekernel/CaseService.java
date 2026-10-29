package com.wellnessz.casekernel;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.AttachmentMeta;
import com.wellnessz.casekernel.model.CaseAttachmentRepository;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseEvents;
import com.wellnessz.casekernel.model.CaseIdentity;
import com.wellnessz.casekernel.model.CaseLink;
import com.wellnessz.casekernel.model.CaseLinkRepository;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.CaseViews;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.SafetyCategory;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseRefGenerator;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.CaseTransitionGuard;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DisclosureLevel;
import com.wellnessz.common.model.Roles;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.masterdata.JobCode;
import com.wellnessz.masterdata.JobCodeRepository;
import com.wellnessz.masterdata.OrgUnit;
import com.wellnessz.masterdata.OrgUnitRepository;
import com.wellnessz.security.abac.AccessDecision;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Case kernel service. Every read goes through the ABAC policy engine and returns the projection matching the
 * granted disclosure level – this is where the "status-only disclosure rule" is enforced for every module.
 * Case creation resolves the mandatory HRIS identity (E01-HLR-05) and numbers the case per unit and year.
 */
@Service
public class CaseService {

    public static final String ROSTERING_DESTINATION = "crew-rostering";
    public static final String HR_DESTINATION = "hr-systems";
    public static final String HYGIENE_DESTINATION = "catering-hygiene";

    private final OhCaseRepository cases;
    private final OrgUnitRepository orgUnits;
    private final EmployeeRepository employees;
    private final JobCodeRepository jobCodes;
    private final CaseLinkRepository links;
    private final CaseAttachmentRepository attachments;
    private final CaseAccess access;
    private final AuditService audit;
    private final EventBus bus;
    private final CaseRefGenerator refs;
    private final CaseTimeline timeline;
    private final List<CaseTransitionGuard> guards;
    private final Clock clock;
    private final ModuleRegistry modules;
    private final List<FitnessPublicationListener> publicationListeners;

    public CaseService(OhCaseRepository cases, OrgUnitRepository orgUnits, EmployeeRepository employees, JobCodeRepository jobCodes,
                       CaseLinkRepository links, CaseAttachmentRepository attachments, CaseAccess access, AuditService audit,
                       EventBus bus, CaseRefGenerator refs, CaseTimeline timeline, List<CaseTransitionGuard> guards, Clock clock,
                       ModuleRegistry modules, List<FitnessPublicationListener> publicationListeners) {
        this.publicationListeners = publicationListeners;
        this.cases = cases;
        this.orgUnits = orgUnits;
        this.employees = employees;
        this.jobCodes = jobCodes;
        this.links = links;
        this.attachments = attachments;
        this.access = access;
        this.audit = audit;
        this.bus = bus;
        this.refs = refs;
        this.timeline = timeline;
        this.guards = guards;
        this.clock = clock;
        this.modules = modules;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<Object> list(CurrentUser user, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 500));
        List<OhCase> candidates;
        if (user.hasAnyRole(Roles.OH_TEAM) || user.hasRole(Roles.OH_ADMIN)) {
            candidates = cases.findAllByOrderByUpdatedAtDesc(page);
        } else if (user.hasAnyRole(Roles.MANAGER, Roles.HR) && user.orgScope() != null) {
            candidates = cases.findInOrgScope(user.orgScope(), page);
        } else if (user.employeeId() != null) {
            candidates = cases.findByEmployeeIdOrderByCreatedAtDesc(user.employeeId());
        } else {
            candidates = List.of();
        }
        List<Object> visible = candidates.stream()
                .map(c -> project(c, access.decide(user, c), false))
                .flatMap(Optional::stream)
                .toList();
        audit.record("CASE_LIST", "CASE", null, AuditService.Outcome.SUCCESS, "returned=" + visible.size());
        return visible;
    }

    @Transactional(readOnly = true)
    public Object get(CurrentUser user, UUID id) {
        OhCase c = cases.findById(id).orElseThrow(() -> ApiException.notFound("Case"));
        AccessDecision d = access.decide(user, c);
        if (!d.permitted()) {
            audit.record("CASE_READ", "CASE", id.toString(), AuditService.Outcome.DENIED, d.reason());
            throw ApiException.notFound("Case"); // 404 rather than 403: do not confirm existence
        }
        audit.record("CASE_READ", "CASE", id.toString(), AuditService.Outcome.SUCCESS, d.disclosure().name());
        return project(c, d, true).orElseThrow();
    }

    @Transactional(readOnly = true)
    public CaseViews.StatusOnly status(CurrentUser user, UUID id) {
        OhCase c = access.require(user, id, AccessPolicy.Action.READ, DisclosureLevel.STATUS_ONLY, "CASE_STATUS_READ");
        audit.record("CASE_STATUS_READ", "CASE", id.toString(), AuditService.Outcome.SUCCESS, null);
        return CaseViews.StatusOnly.of(c);
    }

    // ------------------------------------------------------------------ creation

    /** Generic case creation used by every E01 intake channel. */
    @Transactional
    public OhCase open(OpenCaseCommand cmd) {
        CaseIdentity identity = resolveIdentity(cmd);
        OhCase c = cases.save(new OhCase(refs.next(identity.subsidiaryCode()), cmd, identity, clock.instant()));
        if (cmd.incidentRef() != null) {
            links.save(new CaseLink(c.getId(), CaseLink.Type.INCIDENT, cmd.incidentRef(), "system", clock.instant()));
        }
        timeline.record(c.getId(), "CASE_OPENED", "Case opened via " + cmd.source() + " (" + c.getCaseType() + ")"
                + (identity.hrisOverride() ? " – HRIS identity not found, flagged for review" : ""));
        bus.publish(new CaseEvents.CaseOpened(c.getId().toString(), c.getCaseRef(), c.getCategory().name(), c.getSource().name(),
                c.getCaseType().name(), c.getUrgency().name(), c.getSubsidiaryCode(), c.getOrgUnitCode(), c.getLocationCode(),
                c.getLanguage(), c.getSafetyCategory() == null ? null : c.getSafetyCategory().name(), c.getEmployeeId()));
        audit.record("CASE_OPENED", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, cmd.source().name());
        return c;
    }

    /** Idempotent creation for upstream triggers (incident, absence, surveillance …) keyed by source + sourceRef. */
    @Transactional
    public OhCase openIdempotent(OpenCaseCommand cmd) {
        if (cmd.sourceRef() == null) {
            return open(cmd);
        }
        return cases.findBySourceAndSourceRef(cmd.source(), cmd.sourceRef()).orElseGet(() -> open(cmd));
    }

    /** Legacy base-framework endpoint kept for compatibility; E01 self-referral supersedes it. */
    @Transactional
    public CaseViews.Self selfReferral(CurrentUser user, CaseCategory category, String description) {
        if (user.employeeId() == null || !user.hasRole(Roles.EMPLOYEE)) {
            throw ApiException.forbidden("Self-referral is available to employees only");
        }
        modules.requireEnabled("E13");
        OhCase c = open(OpenCaseCommand.builder(user.employeeId(), category, CaseSource.SELF_REFERRAL)
                .employeeName(user.displayName())
                .fallbackOrgUnitCode(user.orgScope() == null ? null : lastSegment(user.orgScope()))
                .clinicalSummary(description).build());
        audit.record("CASE_SELF_REFERRAL", "CASE", c.getId().toString(), AuditService.Outcome.SUCCESS, category.name());
        return CaseViews.Self.of(c);
    }

    // ------------------------------------------------------------------ changes

    @Transactional
    public CaseViews.StatusOnly recordFitness(CurrentUser user, UUID id, FitnessStatus fitness, LocalDate until) {
        if (!user.hasAnyRole(Roles.FITNESS_DECIDERS)) {
            throw ApiException.forbidden("Only OH clinicians can record fitness outcomes");
        }
        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "FITNESS_RECORDED");
        c.recordFitness(fitness, until, clock.instant());
        publishFitness(c);
        timeline.record(c.getId(), "FITNESS_RECORDED", "Fitness status recorded: " + fitness);
        audit.record("FITNESS_RECORDED", "CASE", id.toString(), AuditService.Outcome.SUCCESS, fitness.name());
        return CaseViews.StatusOnly.of(c);
    }

    /** Status-only feeds to rostering / HR / catering hygiene (allow-listed by the outbound guard). */
    public void publishFitness(OhCase c) {
        publishFitness(c, FitnessFeed.NONE);
    }

    /**
     * Publishes the status-only fitness feeds. Crew rostering receives a {@link CaseEvents.RosterStatusChanged} with
     * validity dates and operational limitation codes (E05-F04); HR and hygiene keep the minimal status payload.
     */
    public void publishFitness(OhCase c, FitnessFeed feed) {
        LocalDate today = LocalDate.now(clock);
        CaseEvents.FitnessStatusChanged event = new CaseEvents.FitnessStatusChanged(c.getCaseRef(), c.getEmployeeId(),
                c.getFitnessStatus().name(), c.getRestrictionUntil(), today);
        bus.publish(event, HR_DESTINATION);
        if (c.getSafetyCategory() == null || c.getSafetyCategory().isAeromedical()) {
            CaseEvents.RosterStatusChanged roster = new CaseEvents.RosterStatusChanged(java.util.UUID.randomUUID().toString(),
                    c.getEmployeeId(), c.getFitnessStatus().name(), FitnessFeed.rosterStatus(c.getFitnessStatus()), c.getRestrictionUntil(),
                    feed.validFrom() == null ? today : feed.validFrom(), feed.validUntil() == null ? c.getRestrictionUntil() : feed.validUntil(),
                    feed.operationalLimitations().isEmpty() ? null : String.join(",", feed.operationalLimitations()), today);
            bus.publish(roster, ROSTERING_DESTINATION);
            publicationListeners.forEach(l -> l.rosterStatusPublished(c, roster));
        }
        if (c.getSafetyCategory() == SafetyCategory.FOOD_HANDLER) {
            bus.publish(event, HYGIENE_DESTINATION);
        }
    }

    /**
     * Applies a fitness decision taken by a business module (E05) after it has performed its own authorisation and
     * sign-off rules: updates the case status, publishes the feeds and records timeline + audit.
     */
    @Transactional
    public CaseViews.StatusOnly applyFitnessDecision(CurrentUser user, UUID id, FitnessStatus fitness, LocalDate until, FitnessFeed feed) {
        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "FITNESS_DECISION_APPLIED");
        c.recordFitness(fitness, until, clock.instant());
        publishFitness(c, feed);
        timeline.record(c.getId(), "FITNESS_RECORDED", "Fitness decision applied: " + fitness);
        audit.record("FITNESS_RECORDED", "CASE", id.toString(), AuditService.Outcome.SUCCESS, fitness.name());
        return CaseViews.StatusOnly.of(c);
    }

    @Transactional
    public CaseViews.StatusOnly transition(CurrentUser user, UUID id, CaseStatus next) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Not permitted to change case status");
        }
        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "CASE_TRANSITION");
        if (next == CaseStatus.MERGED) {
            throw ApiException.badRequest("use_merge", "Use the merge action to merge duplicates");
        }
        if (!c.getStatus().canTransitionTo(next)) {
            throw ApiException.conflict("invalid_transition", "Transition " + c.getStatus() + " -> " + next + " not allowed");
        }
        guards.forEach(g -> g.check(c, next));
        c.transition(next, clock.instant());
        timeline.record(c.getId(), "STATUS_CHANGED", "Status changed to " + next);
        audit.record("CASE_TRANSITION", "CASE", id.toString(), AuditService.Outcome.SUCCESS, next.name());
        return CaseViews.StatusOnly.of(c);
    }

    @Transactional
    public CaseViews.LinkView link(CurrentUser user, UUID id, CaseLink.Type type, String ref) {
        OhCase c = access.clinical(user, id, AccessPolicy.Action.UPDATE, "CASE_LINK");
        CaseLink l = links.save(new CaseLink(c.getId(), type, ref, user.username(), clock.instant()));
        timeline.record(c.getId(), "LINKED", "Linked " + type + " " + ref);
        audit.record("CASE_LINK", "CASE", id.toString(), AuditService.Outcome.SUCCESS, type.name());
        return new CaseViews.LinkView(l.getType().name(), l.getRef(), l.getCreatedAt());
    }

    // ------------------------------------------------------------------ helpers

    private CaseIdentity resolveIdentity(OpenCaseCommand cmd) {
        Optional<Employee> emp = employees.findById(cmd.employeeId());
        if (emp.isPresent()) {
            Employee e = emp.get();
            SafetyCategory safety = e.getJobCode() == null ? null
                    : jobCodes.findById(e.getJobCode()).map(JobCode::getSafetyCategory).map(SafetyCategory::parse).orElse(null);
            return new CaseIdentity(e.getDisplayName(), e.getSubsidiaryCode(), e.getDepartmentCode(), path(e.getDepartmentCode()),
                    e.getCostCentre(), e.getJobCode(), e.getLocationCode(), e.getShiftPattern(), e.getPreferredLanguage(),
                    safety, false);
        }
        // employee unknown to HRIS: manual identity, flagged for review (E01-BL-017)
        String org = cmd.fallbackOrgUnitCode();
        return new CaseIdentity(cmd.employeeName(), null, org, path(org), null, null, null, null, null, null, true);
    }

    private String path(String orgUnitCode) {
        return orgUnitCode == null ? null : orgUnits.findById(orgUnitCode).map(OrgUnit::getPath).orElse(null);
    }

    private Optional<Object> project(OhCase c, AccessDecision d, boolean withDetails) {
        if (!d.permitted()) {
            return Optional.empty();
        }
        return Optional.of(switch (d.disclosure()) {
            case STATUS_ONLY -> CaseViews.StatusOnly.of(c);
            case SELF -> CaseViews.Self.of(c);
            case CLINICAL, RESTRICTED_CLINICAL -> withDetails
                    ? CaseViews.Clinical.of(c, d.disclosure(),
                        links.findByCaseIdOrderByCreatedAt(c.getId()).stream()
                                .map(l -> new CaseViews.LinkView(l.getType().name(), l.getRef(), l.getCreatedAt())).toList(),
                        attachmentsOf(c))
                    : CaseViews.Clinical.of(c, d.disclosure());
            case NONE -> throw new IllegalStateException();
        });
    }

    private List<AttachmentMeta> attachmentsOf(OhCase c) {
        return attachments.findMeta(c.getId());
    }

    private static String lastSegment(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }
}
