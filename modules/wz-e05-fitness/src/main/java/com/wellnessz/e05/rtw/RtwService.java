package com.wellnessz.e05.rtw;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.support.CaseAccess;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.casekernel.support.EmployeeRecordAccess;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.common.model.Roles;
import com.wellnessz.documents.DocumentService;
import com.wellnessz.documents.model.DocumentShare;
import com.wellnessz.e05.model.RtwPlan;
import com.wellnessz.e05.model.RtwPlanRepository;
import com.wellnessz.e05.model.RtwReview;
import com.wellnessz.e05.model.RtwReviewRepository;
import com.wellnessz.e05.restriction.RestrictionService;
import com.wellnessz.e05.support.FitnessSupport;
import com.wellnessz.e05.support.RestrictionRef;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Return-to-work plans (E05-BL-007/009): phased plan builder (weeks, hours, functional duties, restrictions), review
 * schedule, activation with an RTW_PLAN document, employee agreement and manager acknowledgement, review completion,
 * overdue list and escalation to the Nursing Head after two days.
 */
@Service
public class RtwService {

    static final int MAX_PHASES = 12;
    static final int MAX_REVIEWS = 20;
    static final int ESCALATE_AFTER_DAYS = 2;
    static final Set<String> REVIEW_OUTCOMES = Set.of("ON_TRACK", "ADJUST", "EXTEND", "COMPLETE");
    private static final TypeReference<List<PhaseView>> PHASES = new TypeReference<>() {
    };

    private final RtwPlanRepository plans;
    private final RtwReviewRepository reviews;
    private final OhCaseRepository cases;
    private final CaseAccess access;
    private final EmployeeRecordAccess records;
    private final CaseTimeline timeline;
    private final RestrictionService restrictions;
    private final DocumentService documents;
    private final FitnessSupport support;
    private final AuditService audit;

    public RtwService(RtwPlanRepository plans, RtwReviewRepository reviews, OhCaseRepository cases, CaseAccess access,
                      EmployeeRecordAccess records, CaseTimeline timeline, RestrictionService restrictions, DocumentService documents,
                      FitnessSupport support, AuditService audit) {
        this.plans = plans;
        this.reviews = reviews;
        this.cases = cases;
        this.access = access;
        this.records = records;
        this.timeline = timeline;
        this.restrictions = restrictions;
        this.documents = documents;
        this.support = support;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ types

    public record PhaseInput(Integer fromWeek, Integer toWeek, BigDecimal hoursPerDay, String duties, List<RestrictionRef.Input> restrictions) {
    }

    public record PhaseView(int fromWeek, int toWeek, BigDecimal hoursPerDay, String duties, List<RestrictionRef.View> restrictions) {
    }

    public record PlanCommand(LocalDate startDate, List<PhaseInput> phases, List<LocalDate> reviewDates, String goal) {
    }

    public record ReviewView(UUID id, LocalDate dueDate, Instant completedAt, String outcome, String note, String completedBy) {
    }

    public record EmployeeAck(Instant at, Boolean agree, String comment) {
    }

    public record ManagerAck(Instant at, String by, String comment) {
    }

    public record PlanView(UUID id, UUID caseId, String caseRef, String employeeId, String employeeName, String status, LocalDate startDate,
                           String goal, List<PhaseView> phases, List<ReviewView> reviews, EmployeeAck employeeAck, ManagerAck managerAck,
                           UUID documentId, String createdBy, Instant createdAt, LocalDate nextReviewDate) {
    }

    public record OverdueReview(UUID planId, UUID reviewId, String caseRef, String employeeName, LocalDate reviewDate, long daysOverdue,
                                String owner, boolean escalated) {
    }

    // ------------------------------------------------------------------ OH plan builder

    @Transactional
    public PlanView create(CurrentUser user, UUID caseId, PlanCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "RTW_PLAN_CREATE");
        RtwPlan p = RtwPlan.create();
        p.setCaseId(c.getId());
        p.setCaseRef(c.getCaseRef());
        p.setEmployeeId(c.getEmployeeId());
        p.setStatus("DRAFT");
        p.setCreatedBy(user.username());
        p.setCreatedAt(support.clock().instant());
        apply(p, cmd);
        planReviews(p, cmd.reviewDates());
        plans.save(p);
        timeline.record(c.getId(), "RTW_PLAN", "Return-to-work plan drafted starting " + p.getStartDate());
        audit.record("RTW_PLAN_CREATE", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(p, Audience.OH);
    }

    @Transactional
    public PlanView update(CurrentUser user, UUID planId, PlanCommand cmd) {
        RtwPlan p = loadOh(user, planId, AccessPolicy.Action.UPDATE, "RTW_PLAN_UPDATE");
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.conflict("not_draft", "Only draft plans can be changed");
        }
        apply(p, cmd);
        planReviews(p, cmd.reviewDates());
        audit.record("RTW_PLAN_UPDATE", "RTW_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(p, Audience.OH);
    }

    private void apply(RtwPlan p, PlanCommand cmd) {
        if (cmd.startDate() == null) {
            throw ApiException.badRequest("start_date_required", "startDate is required");
        }
        String goal = FitnessSupport.functional("goal", cmd.goal(), 1000);
        if (goal == null) {
            throw ApiException.badRequest("goal_required", "Describe the goal of the plan");
        }
        if (cmd.phases() == null || cmd.phases().isEmpty() || cmd.phases().size() > MAX_PHASES) {
            throw ApiException.badRequest("invalid_phases", "A plan has between 1 and " + MAX_PHASES + " phases");
        }
        List<PhaseView> phases = new ArrayList<>();
        int i = 0;
        for (PhaseInput in : cmd.phases()) {
            String field = "phases[" + i++ + "]";
            if (in == null || in.fromWeek() == null || in.toWeek() == null || in.fromWeek() < 1 || in.toWeek() < in.fromWeek() || in.toWeek() > 52) {
                throw ApiException.badRequest("invalid_phase", field + ": weeks must satisfy 1 <= fromWeek <= toWeek <= 52");
            }
            if (in.hoursPerDay() == null || in.hoursPerDay().signum() <= 0 || in.hoursPerDay().compareTo(BigDecimal.valueOf(24)) > 0) {
                throw ApiException.badRequest("invalid_phase", field + ": hoursPerDay must be between 0 and 24");
            }
            String duties = FitnessSupport.functional(field + ".duties", in.duties(), 1000);
            if (duties == null) {
                throw ApiException.badRequest("invalid_phase", field + ": duties are required");
            }
            phases.add(new PhaseView(in.fromWeek(), in.toWeek(), in.hoursPerDay(), duties, support.resolve(in.restrictions())));
        }
        p.setStartDate(cmd.startDate());
        p.setGoal(goal);
        p.setPhases(support.writeJson(phases));
    }

    /** Planned review dates are kept on the draft; review rows (tracked, reminded, escalated) are created on activation. */
    private static void planReviews(RtwPlan p, List<LocalDate> dates) {
        if (dates != null && dates.size() > MAX_REVIEWS) {
            throw ApiException.badRequest("too_many_reviews", "At most " + MAX_REVIEWS + " review dates");
        }
        TreeSet<LocalDate> sorted = new TreeSet<>();
        for (LocalDate d : dates == null ? List.<LocalDate>of() : dates) {
            if (d == null || d.isBefore(p.getStartDate())) {
                throw ApiException.badRequest("invalid_review_date", "Review dates must not be before the plan start date");
            }
            sorted.add(d);
        }
        p.setReviewDates(sorted.isEmpty() ? null : String.join(",", sorted.stream().map(LocalDate::toString).toList()));
    }

    static List<LocalDate> plannedReviews(RtwPlan p) {
        return p.getReviewDates() == null || p.getReviewDates().isBlank() ? List.of()
                : java.util.Arrays.stream(p.getReviewDates().split(",")).map(LocalDate::parse).toList();
    }

    @Transactional
    public PlanView activate(CurrentUser user, UUID planId) {
        RtwPlan p = loadOh(user, planId, AccessPolicy.Action.UPDATE, "RTW_PLAN_ACTIVATE");
        if (!"DRAFT".equals(p.getStatus())) {
            throw ApiException.conflict("not_draft", "Only draft plans can be activated");
        }
        for (RtwPlan other : plans.findByCaseIdOrderByCreatedAtAsc(p.getCaseId())) {
            if (!other.getId().equals(p.getId()) && "ACTIVE".equals(other.getStatus())) {
                other.setStatus("CANCELLED");
            }
        }
        p.setStatus("ACTIVE");
        p.setActivatedAt(support.clock().instant());
        List<PhaseView> phases = phases(p);
        List<RtwReview> rs = new ArrayList<>();
        for (LocalDate d : plannedReviews(p)) {
            RtwReview r = RtwReview.create();
            r.setPlanId(p.getId());
            r.setDueDate(d);
            rs.add(reviews.save(r));
        }
        Employee e = support.employee(p.getEmployeeId()).orElse(null);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("employeeName", e == null ? p.getEmployeeId() : e.getDisplayName());
        f.put("caseRef", p.getCaseRef());
        f.put("startDate", p.getStartDate().toString());
        f.put("goal", p.getGoal());
        f.put("phases", String.join(" | ", phases.stream().map(RtwService::describe).toList()));
        if (!rs.isEmpty()) {
            f.put("reviewDates", String.join(", ", rs.stream().map(r -> r.getDueDate().toString()).toList()));
        }
        f.put("contact", support.staffName(user.username()) + ", Occupational Health");
        DocumentService.DocumentView doc = documents.generate(user, "RTW_PLAN", p.getEmployeeId(), p.getCaseId(), f);
        p.setDocumentId(doc.id());
        if (user.hasAnyRole(Roles.CLINICIANS)) {
            documents.sign(user, doc.id());
            documents.share(user, doc.id(), DocumentShare.Target.EMPLOYEE, null, null);
            if (e != null && e.getManagerEmployeeId() != null) {
                documents.share(user, doc.id(), DocumentShare.Target.MANAGER, null, null);
            }
        }
        String due = support.today().plusDays(3).toString();
        support.notifyEmployee(p.getEmployeeId(), "rtw.plan-ack", Map.of("caseRef", p.getCaseRef(), "dueDate", due));
        support.notifyManagerOf(p.getEmployeeId(), "rtw.plan-ack", Map.of("caseRef", p.getCaseRef(), "dueDate", due));
        timeline.record(p.getCaseId(), "RTW_PLAN", "Return-to-work plan activated – awaiting employee and manager acknowledgement");
        audit.record("RTW_PLAN_ACTIVATE", "RTW_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(p, Audience.OH);
    }

    static String describe(PhaseView ph) {
        String r = ph.restrictions().isEmpty() ? "" : " (" + FitnessSupport.labels(ph.restrictions()) + ")";
        return "Weeks " + ph.fromWeek() + "-" + ph.toWeek() + ": " + ph.hoursPerDay().stripTrailingZeros().toPlainString() + " h/day, "
                + ph.duties() + r;
    }

    @Transactional(readOnly = true)
    public List<PlanView> forCase(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "RTW_PLANS_READ");
        return plans.findByCaseIdOrderByCreatedAtAsc(caseId).stream().map(p -> view(p, Audience.OH)).toList();
    }

    @Transactional
    public PlanView completeReview(CurrentUser user, UUID planId, UUID reviewId, String outcome, String note) {
        RtwPlan p = loadOh(user, planId, AccessPolicy.Action.UPDATE, "RTW_REVIEW_COMPLETE");
        if (!"ACTIVE".equals(p.getStatus())) {
            throw ApiException.conflict("not_active", "Reviews are completed on active plans");
        }
        if (!REVIEW_OUTCOMES.contains(outcome)) {
            throw ApiException.badRequest("invalid_outcome", "Unknown review outcome");
        }
        RtwReview r = reviews.findById(reviewId).filter(x -> x.getPlanId().equals(planId))
                .orElseThrow(() -> ApiException.notFound("Review"));
        if (r.getCompletedAt() != null) {
            throw ApiException.conflict("already_completed", "Review already completed");
        }
        r.setOutcome(outcome);
        r.setNote(FitnessSupport.functional("note", note, 1000));
        r.setCompletedAt(support.clock().instant());
        r.setCompletedBy(user.username());
        if ("COMPLETE".equals(outcome)) {
            p.setStatus("COMPLETED");
        }
        timeline.record(p.getCaseId(), "RTW_REVIEW", "Return-to-work review " + r.getDueDate() + ": " + outcome);
        audit.record("RTW_REVIEW_COMPLETE", "RTW_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, outcome);
        return view(p, Audience.OH);
    }

    @Transactional(readOnly = true)
    public List<OverdueReview> overdue(CurrentUser user) {
        if (!user.hasAnyRole(Roles.OH_TEAM)) {
            throw ApiException.forbidden("Occupational Health only");
        }
        LocalDate today = support.today();
        List<OverdueReview> out = new ArrayList<>();
        for (RtwReview r : reviews.findByCompletedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(today.minusDays(1))) {
            RtwPlan p = plans.findById(r.getPlanId()).orElse(null);
            if (p == null || !"ACTIVE".equals(p.getStatus())) {
                continue;
            }
            Employee e = support.employee(p.getEmployeeId()).orElse(null);
            if (e == null || !records.canSeeClinical(user, e, DataClassification.CLINICAL)) {
                continue;
            }
            out.add(new OverdueReview(p.getId(), r.getId(), p.getCaseRef(), e.getDisplayName(), r.getDueDate(),
                    ChronoUnit.DAYS.between(r.getDueDate(), today), owner(p), r.getEscalatedAt() != null));
        }
        audit.record("RTW_OVERDUE_READ", "RTW_PLAN", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    private String owner(RtwPlan p) {
        return cases.findById(p.getCaseId()).map(OhCase::getOwnerUsername).orElse(p.getCreatedBy());
    }

    public record ReviewRun(int reminded, int escalated) {
    }

    /** Daily: reminds the case owner of reviews due (content-free) and escalates reviews overdue > 2 days to the Nursing Head. */
    @Transactional
    public ReviewRun processReviews(LocalDate today) {
        int reminded = 0;
        int escalated = 0;
        Instant now = support.clock().instant();
        for (RtwReview r : reviews.findByCompletedAtIsNullAndDueDateLessThanEqualOrderByDueDateAsc(today.plusDays(1))) {
            RtwPlan p = plans.findById(r.getPlanId()).orElse(null);
            if (p == null || !"ACTIVE".equals(p.getStatus())) {
                continue;
            }
            Map<String, String> params = Map.of("caseRef", p.getCaseRef(), "dueDate", r.getDueDate().toString());
            if (r.getRemindedAt() == null) {
                support.notifyStaff(owner(p), "rtw.review-due", params);
                r.setRemindedAt(now);
                reminded++;
            }
            if (r.getEscalatedAt() == null && ChronoUnit.DAYS.between(r.getDueDate(), today) > ESCALATE_AFTER_DAYS) {
                support.notifyRole(Roles.NURSING_HEAD, "rtw.review-due", params);
                r.setEscalatedAt(now);
                timeline.record(p.getCaseId(), "RTW_REVIEW_ESCALATED", "Return-to-work review due " + r.getDueDate()
                        + " is overdue – escalated to the Nursing Head");
                escalated++;
            }
        }
        return new ReviewRun(reminded, escalated);
    }

    // ------------------------------------------------------------------ employee

    @Transactional(readOnly = true)
    public List<PlanView> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        List<PlanView> out = plans.findByEmployeeIdAndStatusInOrderByCreatedAtDesc(user.employeeId(), Set.of("ACTIVE")).stream()
                .map(p -> view(p, Audience.EMPLOYEE)).toList();
        audit.record("RTW_PLANS_SELF_READ", "EMPLOYEE", user.employeeId(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public PlanView employeeAcknowledge(CurrentUser user, UUID planId, boolean agree, String comment) {
        RtwPlan p = plans.findById(planId).filter(x -> user.employeeId() != null && user.employeeId().equals(x.getEmployeeId()))
                .orElseThrow(() -> ApiException.notFound("RTW plan"));
        if (!"ACTIVE".equals(p.getStatus())) {
            throw ApiException.conflict("not_active", "Only active plans can be acknowledged");
        }
        p.setEmployeeAckAt(support.clock().instant());
        p.setEmployeeAgree(agree);
        p.setEmployeeComment(FitnessSupport.clinical("comment", comment, 1000));
        timeline.record(p.getCaseId(), "RTW_PLAN_ACK", "Employee " + (agree ? "agreed to" : "did not agree to") + " the return-to-work plan");
        audit.record("RTW_PLAN_EMPLOYEE_ACK", "RTW_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, agree ? "AGREE" : "DISAGREE");
        return view(p, Audience.EMPLOYEE);
    }

    // ------------------------------------------------------------------ manager / HR

    @Transactional(readOnly = true)
    public List<PlanView> workspace(CurrentUser user) {
        if (!user.hasAnyRole(Roles.MANAGER, Roles.HR)) {
            throw ApiException.forbidden("Managers and HR only");
        }
        List<PlanView> out = new ArrayList<>();
        for (RtwPlan p : plans.findByStatusInOrderByCreatedAtDesc(Set.of("ACTIVE"))) {
            Employee e = support.employee(p.getEmployeeId()).orElse(null);
            if (e != null && restrictions.inScope(user, e)) {
                out.add(view(p, Audience.WORKSPACE));
            }
        }
        audit.record("RTW_PLANS_WORKSPACE_READ", "RTW_PLAN", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    @Transactional
    public PlanView managerAcknowledge(CurrentUser user, UUID planId, String comment) {
        if (!user.hasRole(Roles.MANAGER)) {
            throw ApiException.forbidden("Line managers acknowledge RTW plans");
        }
        RtwPlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("RTW plan"));
        Employee e = support.employee(p.getEmployeeId()).orElse(null);
        if (e == null || !restrictions.inScope(user, e)) {
            audit.record("RTW_PLAN_MANAGER_ACK", "RTW_PLAN", planId.toString(), AuditService.Outcome.DENIED, "Outside scope");
            throw ApiException.notFound("RTW plan");
        }
        if (!"ACTIVE".equals(p.getStatus())) {
            throw ApiException.conflict("not_active", "Only active plans can be acknowledged");
        }
        p.setManagerAckAt(support.clock().instant());
        p.setManagerAckBy(user.username());
        p.setManagerComment(FitnessSupport.functional("comment", comment, 1000));
        timeline.record(p.getCaseId(), "RTW_PLAN_ACK", "Line manager acknowledged the return-to-work plan");
        audit.record("RTW_PLAN_MANAGER_ACK", "RTW_PLAN", planId.toString(), AuditService.Outcome.SUCCESS, null);
        return view(p, Audience.WORKSPACE);
    }

    // ------------------------------------------------------------------ helpers

    enum Audience { OH, EMPLOYEE, WORKSPACE }

    private RtwPlan loadOh(CurrentUser user, UUID planId, AccessPolicy.Action action, String auditAction) {
        RtwPlan p = plans.findById(planId).orElseThrow(() -> ApiException.notFound("RTW plan"));
        access.clinical(user, p.getCaseId(), action, auditAction);
        return p;
    }

    private List<PhaseView> phases(RtwPlan p) {
        return support.readJson(p.getPhases(), PHASES);
    }

    private PlanView view(RtwPlan p, Audience audience) {
        List<ReviewView> rs = "DRAFT".equals(p.getStatus())
                ? plannedReviews(p).stream().map(d -> new ReviewView(null, d, null, null, null, null)).toList()
                : reviews.findByPlanIdOrderByDueDateAsc(p.getId()).stream().map(r -> new ReviewView(r.getId(), r.getDueDate(), r.getCompletedAt(),
                        r.getOutcome(), r.getNote(), r.getCompletedBy())).toList();
        LocalDate next = rs.stream().filter(r -> r.completedAt() == null).map(ReviewView::dueDate).findFirst().orElse(null);
        // the employee's free-text comment is visible to the employee and OH only, never to managers / HR
        EmployeeAck ea = new EmployeeAck(p.getEmployeeAckAt(), p.getEmployeeAgree(), audience == Audience.WORKSPACE ? null : p.getEmployeeComment());
        ManagerAck ma = new ManagerAck(p.getManagerAckAt(), p.getManagerAckBy(), p.getManagerComment());
        return new PlanView(p.getId(), p.getCaseId(), p.getCaseRef(), p.getEmployeeId(), support.employeeName(p.getEmployeeId()), p.getStatus(),
                p.getStartDate(), p.getGoal(), phases(p), rs, ea, ma, p.getDocumentId(),
                audience == Audience.OH ? p.getCreatedBy() : null, p.getCreatedAt(), next);
    }
}
