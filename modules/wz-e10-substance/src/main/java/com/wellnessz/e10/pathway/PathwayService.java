package com.wellnessz.e10.pathway;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.FitnessStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.casekernel.support.SupportProgrammeReferral;
import com.wellnessz.e10.E10Events;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.DutyStatusRecord;
import com.wellnessz.e10.model.E10DutyStatusRepository;
import com.wellnessz.e10.model.E10RtdPlanRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.RtdPlan;
import com.wellnessz.e10.model.SubstanceEnums.DutyStatus;
import com.wellnessz.e10.model.SubstanceEnums.PlanStatus;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.disclosure.DisclosureLogService;

/**
 * Positive / refusal pathway (E10-F04), synchronous with the MRO verdict: restricted SUBSTANCE case, duty status REMOVED via
 * the status-only fitness feeds (crew rostering / HR – {@code CaseService.publishFitness}), {@code testing.status} notices to HR
 * and crew scheduling (disclosure-logged), referral into the support programme through the kernel SPI and a draft
 * return-to-duty plan. Also the way back: a verified negative RETURN_TO_DUTY test makes the employee AVAILABLE again.
 */
@Service("e10PathwayService")
public class PathwayService {

    public static final String CREW_SCHEDULING_EMAIL_KEY = "notifications.crew-scheduling.email";
    public static final String HR_EMAIL_KEY = "substance.hr-status-email";
    private static final Logger log = LoggerFactory.getLogger(PathwayService.class);

    private final CaseService cases;
    private final OhCaseRepository caseRepository;
    private final E10DutyStatusRepository duty;
    private final E10RtdPlanRepository plans;
    private final E10TestRepository tests;
    private final ObjectProvider<SupportProgrammeReferral> referrals;
    private final DisclosureLogService disclosures;
    private final EventBus bus;
    private final SubstanceSupport support;
    private final AuditService audit;

    public PathwayService(CaseService cases, OhCaseRepository caseRepository, E10DutyStatusRepository duty, E10RtdPlanRepository plans,
                          E10TestRepository tests, ObjectProvider<SupportProgrammeReferral> referrals, DisclosureLogService disclosures,
                          EventBus bus, SubstanceSupport support, AuditService audit) {
        this.cases = cases;
        this.caseRepository = caseRepository;
        this.duty = duty;
        this.plans = plans;
        this.tests = tests;
        this.referrals = referrals;
        this.disclosures = disclosures;
        this.bus = bus;
        this.support = support;
        this.audit = audit;
    }

    /** VERIFIED_POSITIVE or REFUSAL. Idempotent per test (re-confirmation after a split specimen does not repeat it). */
    @Transactional
    public RtdPlan positive(DaTest t, String actor) {
        boolean refusal = TestStatus.REFUSAL.name().equals(t.getStatus());
        OhCase c = cases.openIdempotent(OpenCaseCommand.builder(t.getEmployeeId(), CaseCategory.SUBSTANCE, CaseSource.DA_TEST)
                .caseType(CaseType.SUBSTANCE).sourceRef("da-test:" + t.getId()).urgency(Urgency.URGENT)
                .initialFitness(FitnessStatus.TEMPORARILY_UNFIT).build());
        t.setCaseId(c.getId());
        setDuty(t.getEmployeeId(), DutyStatus.REMOVED, c, actor);
        if (t.getSupportCaseId() == null) {
            t.setSupportCaseId(refer(actor, t, c.getId(), refusal ? "SUBSTANCE_REFUSAL" : "SUBSTANCE_POSITIVE").orElse(null));
        }
        RtdPlan plan = plans.findFirstByTestId(t.getId()).orElseGet(() -> {
            Instant now = support.now();
            RtdPlan p = RtdPlan.create();
            p.setRef(support.nextRef("RTD"));
            p.setEmployeeId(t.getEmployeeId());
            p.setCaseId(c.getId());
            p.setTestId(t.getId());
            p.setStatus(PlanStatus.DRAFT.name());
            p.setCreatedBy(actor);
            p.setCreatedAt(now);
            p.setUpdatedAt(now);
            return plans.save(p);
        });
        // an open plan of an earlier positive is superseded by the new one
        if (t.getRtdPlanId() != null) {
            plans.findById(t.getRtdPlanId()).filter(p -> !p.getId().equals(plan.getId()))
                    .filter(p -> !PlanStatus.COMPLETED.name().equals(p.getStatus()) && !PlanStatus.CANCELLED.name().equals(p.getStatus()))
                    .ifPresent(p -> {
                        p.setStatus(PlanStatus.CANCELLED.name());
                        p.setUpdatedAt(support.now());
                    });
        }
        audit.recordAs(actor, "MRO", refusal ? "DA_REFUSAL_PATHWAY" : "DA_POSITIVE_PATHWAY", "DA_TEST", t.getId().toString(),
                AuditService.Outcome.SUCCESS, "case=" + c.getCaseRef());
        return plan;
    }

    private Optional<UUID> refer(String actor, DaTest t, UUID caseId, String reason) {
        SupportProgrammeReferral spi = referrals.orderedStream().findFirst().orElse(null);
        if (spi == null) {
            audit.recordAs(actor, "MRO", "DA_SUPPORT_REFERRAL", "DA_TEST", t.getId().toString(), AuditService.Outcome.FAILURE,
                    "support programme module not deployed");
            return Optional.empty();
        }
        try {
            Optional<UUID> id = spi.refer(actor, t.getEmployeeId(), caseId, reason, "DA_TEST", t.getRef());
            audit.recordAs(actor, "MRO", "DA_SUPPORT_REFERRAL", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, reason);
            return id;
        } catch (RuntimeException e) {
            log.warn("E10 support referral for {} failed: {}", t.getRef(), e.getClass().getSimpleName());
            audit.recordAs(actor, "MRO", "DA_SUPPORT_REFERRAL", "DA_TEST", t.getId().toString(), AuditService.Outcome.FAILURE,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** VERIFIED_NEGATIVE: return-to-duty test → AVAILABLE; follow-up test → plan completion when all are done. */
    @Transactional
    public void negative(DaTest t, String actor) {
        if (t.getRtdPlanId() == null) {
            return;
        }
        RtdPlan p = plans.findById(t.getRtdPlanId()).orElse(null);
        if (p == null) {
            return;
        }
        Instant now = support.now();
        if (Reason.RETURN_TO_DUTY.name().equals(t.getReason()) && PlanStatus.RTD_PENDING.name().equals(p.getStatus())) {
            p.setStatus(p.getFollowUpTests() > 0 ? PlanStatus.MONITORING.name() : PlanStatus.COMPLETED.name());
            p.setReturnedAt(now);
            if (p.getFollowUpTests() == 0) {
                p.setCompletedAt(now);
            }
            p.setUpdatedAt(now);
            setDuty(p.getEmployeeId(), DutyStatus.AVAILABLE, caseOf(p.getCaseId()), actor);
        } else if (Reason.FOLLOW_UP.name().equals(t.getReason())) {
            completeIfDone(p);
        }
    }

    /** Plan completion: every follow-up test verified negative (or closed) after the return to duty. */
    public void completeIfDone(RtdPlan p) {
        if (!PlanStatus.MONITORING.name().equals(p.getStatus())) {
            return;
        }
        List<DaTest> followUps = tests.findByRtdPlanIdOrderByScheduledForAsc(p.getId()).stream()
                .filter(x -> Reason.FOLLOW_UP.name().equals(x.getReason())).toList();
        boolean done = followUps.size() >= p.getFollowUpTests() && followUps.stream().allMatch(x -> TestStatus.VERIFIED_NEGATIVE.name().equals(x.getStatus())
                || TestStatus.CANCELLED.name().equals(x.getStatus()));
        if (done) {
            p.setStatus(PlanStatus.COMPLETED.name());
            p.setCompletedAt(support.now());
            p.setUpdatedAt(support.now());
        }
    }

    /** A positive that the split specimen failed to reconfirm is cancelled: duty status restored, plan cancelled. */
    @Transactional
    public void reverse(DaTest t, String actor) {
        if (t.getCaseId() == null) {
            return;
        }
        Instant now = support.now();
        plans.findFirstByTestId(t.getId()).ifPresent(p -> {
            p.setStatus(PlanStatus.CANCELLED.name());
            p.setUpdatedAt(now);
            tests.findByRtdPlanIdOrderByScheduledForAsc(p.getId()).stream()
                    .filter(x -> TestStatus.OPEN.contains(TestStatus.valueOf(x.getStatus())))
                    .forEach(x -> {
                        x.setStatus(TestStatus.CANCELLED.name());
                        x.setUpdatedAt(now);
                    });
        });
        setDuty(t.getEmployeeId(), DutyStatus.AVAILABLE, caseOf(t.getCaseId()), actor);
        audit.recordAs(actor, "MRO", "DA_POSITIVE_REVERSED", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, null);
    }

    OhCase caseOf(UUID caseId) {
        return caseId == null ? null : caseRepository.findById(caseId).orElse(null);
    }

    /**
     * Records the duty status, mirrors it on the restricted case's fitness (REMOVED → temporarily unfit, RETURN_PENDING →
     * under review, AVAILABLE → fit), publishes the status-only feeds and notifies HR / crew scheduling.
     */
    public void setDuty(String employeeId, DutyStatus status, OhCase c, String actor) {
        Instant now = support.now();
        DutyStatusRecord r = duty.findById(employeeId).orElseGet(() -> {
            DutyStatusRecord n = new DutyStatusRecord();
            n.setEmployeeId(employeeId);
            return n;
        });
        boolean changed = !status.name().equals(r.getStatus());
        r.setStatus(status.name());
        if (changed || r.getSince() == null) {
            r.setSince(now);
        }
        if (c != null) {
            r.setCaseId(c.getId());
        }
        r.setUpdatedBy(actor);
        r.setUpdatedAt(now);
        duty.save(r);
        if (c != null) {
            FitnessStatus fitness = switch (status) {
                case REMOVED -> FitnessStatus.TEMPORARILY_UNFIT;
                case RETURN_PENDING -> FitnessStatus.UNDER_REVIEW;
                case AVAILABLE -> FitnessStatus.FIT;
            };
            if (c.getFitnessStatus() != fitness || changed) {
                c.recordFitness(fitness, null, now);
                cases.publishFitness(c);
            }
        }
        if (changed) {
            bus.publish(new E10Events.DutyStatusChanged(employeeId, status.name(), now.toString()));
            Map<String, String> params = Map.of("employeeName", support.name(employeeId), "unit", support.unit(employeeId));
            String title = "Duty status " + status.name();
            if (support.notifyMailbox(CREW_SCHEDULING_EMAIL_KEY, "crew-scheduling", "testing.status", params)) {
                disclosures.record(employeeId, "CREW_SCHEDULING", "Crew scheduling mailbox", "DUTY_STATUS", employeeId, title, "DUTY_STATUS", null, actor);
            }
            if (support.notifyMailbox(HR_EMAIL_KEY, "hr-status", "testing.status", params)) {
                disclosures.record(employeeId, "HR", "HR status mailbox", "DUTY_STATUS", employeeId, title, "DUTY_STATUS", null, actor);
            }
            audit.recordAs(actor, "MODULE", "DA_DUTY_STATUS", "EMPLOYEE", employeeId, AuditService.Outcome.SUCCESS, status.name());
        }
    }
}
