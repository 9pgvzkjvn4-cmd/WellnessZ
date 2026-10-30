package com.wellnessz.e10.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10RtdPlanRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.SubstanceEnums.PlanStatus;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.TestingPolicy;
import com.wellnessz.e10.policy.PolicyService;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews;
import com.wellnessz.e10.support.TestingViews.MyNotice;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Time-driven parts of the programme: unannounced follow-up tests are notified on their (random) date, requirements whose
 * window has closed without a collection become WINDOW_MISSED; and the employee's own notices.
 */
@Service("e10ScheduleService")
public class ScheduleService {

    /** Random / follow-up / return-to-duty tests get a short grace period after the report-by time before they are closed. */
    static final Duration GRACE = Duration.ofHours(4);

    private final E10TestRepository tests;
    private final E10RtdPlanRepository plans;
    private final PolicyService policies;
    private final TestCore core;
    private final SubstanceSupport support;
    private final AuditService audit;

    public ScheduleService(E10TestRepository tests, E10RtdPlanRepository plans, PolicyService policies, TestCore core, SubstanceSupport support,
                           AuditService audit) {
        this.tests = tests;
        this.plans = plans;
        this.policies = policies;
        this.core = core;
        this.support = support;
        this.audit = audit;
    }

    @Transactional
    public int notifyDueFollowUps() {
        int n = 0;
        for (DaTest t : tests.findByStatusAndScheduledForLessThanEqual(TestStatus.REQUIRED.name(), support.today())) {
            int lead = policies.policyFor(support.employee(t.getEmployeeId()).orElse(null)).map(TestingPolicy::getLeadTimeMinutes).orElse(120);
            core.notifyEmployee(t, lead);
            n++;
        }
        return n;
    }

    @Transactional
    public int closeMissedWindows() {
        Instant now = support.now();
        int n = 0;
        for (DaTest t : tests.findByStatusInOrderByDueAtAsc(TestStatus.PENDING_COLLECTION.stream().map(Enum::name).toList())) {
            boolean eventDriven = t.getDrugDueAt() != null || t.getAlcoholDueAt() != null;
            Instant last = t.getDrugDueAt() != null ? t.getDrugDueAt() : t.getAlcoholDueAt() != null ? t.getAlcoholDueAt() : t.getDueAt();
            if (last == null || TestStatus.REQUIRED.name().equals(t.getStatus())) {
                continue;
            }
            if (now.isAfter(eventDriven ? last : last.plus(GRACE))) {
                core.transition(t, TestStatus.WINDOW_MISSED);
                if (Reason.RETURN_TO_DUTY.name().equals(t.getReason()) && t.getRtdPlanId() != null) {
                    plans.findById(t.getRtdPlanId()).filter(p -> PlanStatus.RTD_PENDING.name().equals(p.getStatus())).ifPresent(p -> {
                        p.setStatus(PlanStatus.ACTIVE.name()); // a new evaluation / return-to-duty test is needed
                        p.setUpdatedAt(now);
                    });
                }
                audit.recordAs("system", "SYSTEM", "DA_WINDOW_MISSED", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, t.getReason());
                n++;
            }
        }
        return n;
    }

    /** The employee's own notices: where and by when to report – never the reason or an outcome. */
    @Transactional(readOnly = true)
    public List<MyNotice> mine(CurrentUser user) {
        if (user.employeeId() == null) {
            return List.of();
        }
        return tests.findByEmployeeIdOrderByCreatedAtDesc(user.employeeId()).stream()
                .filter(t -> t.getNotifiedAt() != null)
                .map(t -> new MyNotice(t.getRef(), TestingViews.publicStatus(t.getStatus()), TestCore.effectiveDue(t), t.getSiteCode()))
                .toList();
    }
}
