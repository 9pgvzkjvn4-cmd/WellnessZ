package com.wellnessz.e10.pathway;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.DutyStatusRecord;
import com.wellnessz.e10.model.E10DutyStatusRepository;
import com.wellnessz.e10.model.E10RtdPlanRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.RtdPlan;
import com.wellnessz.e10.model.SubstanceEnums.DutyStatus;
import com.wellnessz.e10.model.SubstanceEnums.EvaluationOutcome;
import com.wellnessz.e10.model.SubstanceEnums.Panel;
import com.wellnessz.e10.model.SubstanceEnums.PlanStatus;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.TestingPolicy;
import com.wellnessz.e10.policy.PolicyService;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.DutyStatusView;
import com.wellnessz.e10.support.TestingViews.PlanTest;
import com.wellnessz.e10.support.TestingViews.RtdPlanView;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Return-to-duty evaluation and follow-up testing (E10-BL-008): the plan records evaluator, treatment provider and the number
 * of unannounced follow-up tests over a number of months; READY needs a VERIFIED_NEGATIVE RETURN_TO_DUTY test before the duty
 * status becomes AVAILABLE. Follow-up dates are randomised within the plan window and re-based on the actual return date.
 */
@Service("e10RtdService")
public class RtdService {

    private static final List<String> DEFAULT_PANELS = List.of(Panel.DRUGS_10.name(), Panel.ALCOHOL_BREATH.name());
    private static final Duration RECENT_RETURN = Duration.ofDays(14);

    private final E10RtdPlanRepository plans;
    private final E10TestRepository tests;
    private final E10DutyStatusRepository duty;
    private final PathwayService pathway;
    private final PolicyService policies;
    private final TestCore core;
    private final DisclosureLogService disclosures;
    private final SubstanceSupport support;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public RtdService(E10RtdPlanRepository plans, E10TestRepository tests, E10DutyStatusRepository duty, PathwayService pathway,
                      PolicyService policies, TestCore core, DisclosureLogService disclosures, SubstanceSupport support, AuditService audit) {
        this.plans = plans;
        this.tests = tests;
        this.duty = duty;
        this.pathway = pathway;
        this.policies = policies;
        this.core = core;
        this.disclosures = disclosures;
        this.support = support;
        this.audit = audit;
    }

    public record PlanCommand(String employeeId, UUID caseId, String evaluator, String treatmentProvider, int followUpTests, int followUpMonths) {
    }

    @Transactional(readOnly = true)
    public List<RtdPlanView> list(String status) {
        List<RtdPlan> list = status == null ? plans.findAllByOrderByCreatedAtDesc() : plans.findByStatusOrderByCreatedAtDesc(status);
        audit.record("DA_RTD_PLANS_READ", "DA_RTD_PLAN", null, AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return list.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public RtdPlanView get(UUID id) {
        RtdPlan p = load(id);
        audit.record("DA_RTD_PLAN_READ", "DA_RTD_PLAN", id.toString(), AuditService.Outcome.SUCCESS, null);
        return view(p);
    }

    @Transactional
    public RtdPlanView define(CurrentUser user, PlanCommand c) {
        if (support.employee(c.employeeId()).isEmpty()) {
            throw SubstanceSupport.unprocessable("unknown_employee", "Unknown employee");
        }
        OhCase kase = c.caseId() == null ? null : pathway.caseOf(c.caseId());
        if (c.caseId() != null && (kase == null || kase.getCategory() != CaseCategory.SUBSTANCE || !c.employeeId().equals(kase.getEmployeeId()))) {
            throw SubstanceSupport.unprocessable("invalid_case", "The case is not a substance-programme case of this employee");
        }
        RtdPlan p = plans.findAllByOrderByCreatedAtDesc().stream()
                .filter(x -> x.getEmployeeId().equals(c.employeeId()))
                .filter(x -> c.caseId() == null || c.caseId().equals(x.getCaseId()))
                .filter(x -> PlanStatus.DRAFT.name().equals(x.getStatus()) || PlanStatus.ACTIVE.name().equals(x.getStatus()))
                .findFirst().orElse(null);
        Instant now = support.now();
        if (p == null) {
            p = RtdPlan.create();
            p.setRef(support.nextRef("RTD"));
            p.setEmployeeId(c.employeeId());
            p.setCaseId(c.caseId());
            p.setCreatedBy(user.username());
            p.setCreatedAt(now);
        }
        p.setEvaluator(c.evaluator().strip());
        p.setTreatmentProvider(c.treatmentProvider() == null || c.treatmentProvider().isBlank() ? null : c.treatmentProvider().strip());
        p.setFollowUpTests(c.followUpTests());
        p.setFollowUpMonths(c.followUpMonths());
        p.setStatus(PlanStatus.ACTIVE.name());
        p.setStartDate(support.today());
        p.setEndDate(FollowUpScheduler.windowEnd(p.getStartDate(), c.followUpMonths()));
        p.setUpdatedAt(now);
        RtdPlan saved = plans.save(p);
        scheduleFollowUps(saved, saved.getStartDate(), user.username());
        audit.record("DA_RTD_PLAN_DEFINED", "DA_RTD_PLAN", saved.getId().toString(), AuditService.Outcome.SUCCESS,
                "followUps=" + c.followUpTests() + " months=" + c.followUpMonths());
        return view(saved);
    }

    /**
     * (Re-)schedules the unannounced follow-up tests on random dates within {@code (from, from + months]}; tests that were
     * already notified or done are kept, only pending ones are re-dated.
     */
    void scheduleFollowUps(RtdPlan p, LocalDate from, String actor) {
        List<DaTest> existing = tests.findByRtdPlanIdOrderByScheduledForAsc(p.getId()).stream()
                .filter(x -> Reason.FOLLOW_UP.name().equals(x.getReason())).toList();
        List<DaTest> pending = existing.stream().filter(x -> TestStatus.REQUIRED.name().equals(x.getStatus())).toList();
        int keep = existing.size() - pending.size();
        int needed = Math.max(0, p.getFollowUpTests() - keep);
        List<LocalDate> dates = FollowUpScheduler.schedule(from, p.getFollowUpMonths(), needed, random);
        List<String> panels = panelsFor(p.getEmployeeId());
        for (int i = 0; i < dates.size(); i++) {
            DaTest t = i < pending.size() ? pending.get(i)
                    : core.create(new TestCore.NewTest(p.getEmployeeId(), roleGroupOf(p.getEmployeeId()), Reason.FOLLOW_UP.name(), panels,
                    "rtd:" + p.getId() + ":fu:" + UUID.randomUUID(), actor));
            t.setRtdPlanId(p.getId());
            t.setScheduledFor(dates.get(i));
            t.setUpdatedAt(support.now());
        }
        for (int i = dates.size(); i < pending.size(); i++) {
            core.transition(pending.get(i), TestStatus.CANCELLED);
        }
    }

    private List<String> panelsFor(String employeeId) {
        Optional<TestingPolicy> p = policies.policyFor(support.employee(employeeId).orElse(null));
        return p.map(x -> SubstanceSupport.split(x.getPanels())).orElse(DEFAULT_PANELS);
    }

    private String roleGroupOf(String employeeId) {
        return policies.policyFor(support.employee(employeeId).orElse(null)).map(TestingPolicy::getRoleGroup).orElse(null);
    }

    @Transactional
    public RtdPlanView evaluate(CurrentUser user, UUID id, String outcome, String note) {
        RtdPlan p = load(id);
        if (!PlanStatus.ACTIVE.name().equals(p.getStatus())) {
            throw ApiException.conflict("invalid_state", "Only an ACTIVE plan can be evaluated (current " + p.getStatus() + ")");
        }
        Instant now = support.now();
        p.setEvaluationOutcome(outcome);
        p.setEvaluationNote(note == null || note.isBlank() ? null : note.strip());
        p.setEvaluatedBy(user.username());
        p.setEvaluatedAt(now);
        p.setUpdatedAt(now);
        if (EvaluationOutcome.READY.name().equals(outcome)) {
            DaTest rtd = p.getRtdTestId() == null ? null : tests.findById(p.getRtdTestId()).orElse(null);
            if (rtd != null && TestStatus.VERIFIED_NEGATIVE.name().equals(rtd.getStatus())) {
                p.setStatus(PlanStatus.RTD_PENDING.name());
                afterReturn(rtd, user.username());
            } else {
                DaTest t = core.create(new TestCore.NewTest(p.getEmployeeId(), roleGroupOf(p.getEmployeeId()), Reason.RETURN_TO_DUTY.name(),
                        panelsFor(p.getEmployeeId()), "rtd:" + p.getId() + ":return:" + UUID.randomUUID(), user.username()));
                t.setRtdPlanId(p.getId());
                core.notifyEmployee(t, policies.policyFor(support.employee(p.getEmployeeId()).orElse(null))
                        .map(TestingPolicy::getLeadTimeMinutes).orElse(120));
                p.setRtdTestId(t.getId());
                p.setStatus(PlanStatus.RTD_PENDING.name());
                pathway.setDuty(p.getEmployeeId(), DutyStatus.RETURN_PENDING, pathway.caseOf(p.getCaseId()), user.username());
            }
        }
        audit.record("DA_RTD_EVALUATION", "DA_RTD_PLAN", id.toString(), AuditService.Outcome.SUCCESS, outcome);
        return view(p);
    }

    /** Called after the return-to-duty test is verified negative: re-base the follow-up dates on the actual return. */
    @Transactional
    public void afterReturn(DaTest rtdTest, String actor) {
        pathway.negative(rtdTest, actor);
        plans.findById(rtdTest.getRtdPlanId()).filter(p -> PlanStatus.MONITORING.name().equals(p.getStatus())).ifPresent(p -> {
            p.setEndDate(FollowUpScheduler.windowEnd(support.today(), p.getFollowUpMonths()));
            scheduleFollowUps(p, support.today(), actor);
        });
    }

    private RtdPlan load(UUID id) {
        return plans.findById(id).orElseThrow(() -> ApiException.notFound("Return-to-duty plan"));
    }

    private RtdPlanView view(RtdPlan p) {
        List<DaTest> planTests = tests.findByRtdPlanIdOrderByScheduledForAsc(p.getId());
        PlanTest rtd = planTests.stream().filter(t -> t.getId().equals(p.getRtdTestId())).findFirst()
                .map(t -> new PlanTest(t.getId(), t.getRef(), t.getReason(), t.getStatus(), t.getScheduledFor())).orElse(null);
        List<PlanTest> followUps = planTests.stream().filter(t -> Reason.FOLLOW_UP.name().equals(t.getReason()))
                .map(t -> new PlanTest(t.getId(), t.getRef(), t.getReason(), t.getStatus(), t.getScheduledFor())).toList();
        String dutyStatus = duty.findById(p.getEmployeeId()).map(DutyStatusRecord::getStatus).orElse(DutyStatus.AVAILABLE.name());
        return new RtdPlanView(p.getId(), p.getRef(), p.getEmployeeId(), support.name(p.getEmployeeId()), p.getCaseId(), p.getStatus(),
                p.getEvaluator(), p.getTreatmentProvider(), p.getFollowUpTests(), p.getFollowUpMonths(), p.getStartDate(), p.getEndDate(),
                p.getEvaluationOutcome(), p.getEvaluatedAt(), p.getEvaluatedBy(), rtd, followUps, dutyStatus, p.getCreatedAt());
    }

    // ------------------------------------------------------------------ HR / crew scheduling

    /**
     * Duty status of employees in the caller's scope – removed, return pending, or back to available within the last 14 days.
     * Status only (never reason, result or plan); every row shown is disclosure-logged.
     */
    @Transactional
    public List<DutyStatusView> dutyStatus(CurrentUser user) {
        Instant cutoff = support.now().minus(RECENT_RETURN);
        List<DutyStatusView> out = new ArrayList<>();
        for (DutyStatusRecord r : duty.findAllByOrderBySinceDesc()) {
            if (DutyStatus.AVAILABLE.name().equals(r.getStatus()) && r.getSince().isBefore(cutoff)) {
                continue;
            }
            Employee e = support.employee(r.getEmployeeId()).orElse(null);
            if (!support.inStatusScope(user, e)) {
                continue;
            }
            out.add(new DutyStatusView(r.getEmployeeId(), e.getDisplayName(), r.getStatus(), r.getSince()));
            String recipient = (user.hasRole(com.wellnessz.common.model.Roles.HR) ? "HR " : "Crew scheduling ") + user.username();
            String title = "Duty status " + r.getStatus() + " since " + r.getSince();
            if (!support.alreadyDisclosed("DUTY_STATUS", r.getEmployeeId(), recipient, title)) {
                disclosures.record(r.getEmployeeId(), user.hasRole(com.wellnessz.common.model.Roles.HR) ? "HR" : "CREW_SCHEDULING", recipient, "DUTY_STATUS", r.getEmployeeId(), title,
                        "DUTY_STATUS", null, user.username());
            }
        }
        audit.record("DA_DUTY_STATUS_READ", "EMPLOYEE", null, AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }
}
