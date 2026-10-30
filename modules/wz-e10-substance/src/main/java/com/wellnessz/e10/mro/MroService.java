package com.wellnessz.e10.mro;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.collection.LabService;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10SpecimenRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.RtdPlan;
import com.wellnessz.e10.model.Specimen;
import com.wellnessz.e10.model.SubstanceEnums.Outcome;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.SubstanceEnums.Validity;
import com.wellnessz.e10.model.SubstanceEnums.Verdict;
import com.wellnessz.e10.pathway.PathwayService;
import com.wellnessz.e10.pathway.RtdService;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.LabResult;
import com.wellnessz.e10.support.TestingViews.MroDecisionView;
import com.wellnessz.e10.support.TestingViews.MroQueueItem;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

import tools.jackson.core.type.TypeReference;

/**
 * Medical review (E10-BL-006): the MRO review queue with laboratory results, breath readings and refusals; verification
 * (incl. prescription check), split-specimen requests and the hand-over to the positive / refusal pathway. Results are
 * readable by the MRO (or a designated OH physician acting as MRO) only; every read is audited.
 */
@Service("e10MroService")
public class MroService {

    private static final TypeReference<List<LabResult>> RESULTS = new TypeReference<>() {
    };
    private static final TypeReference<SplitPayload> SPLIT = new TypeReference<>() {
    };
    private static final EnumSet<Verdict> POSITIVE_VERDICTS = EnumSet.of(Verdict.POSITIVE, Verdict.POSITIVE_DILUTE, Verdict.REFUSAL_ADULTERATED,
            Verdict.REFUSAL_SUBSTITUTED);

    private final E10TestRepository tests;
    private final E10SpecimenRepository specimens;
    private final TestCore core;
    private final LabService lab;
    private final PathwayService pathway;
    private final RtdService rtd;
    private final SubstanceSupport support;
    private final AuditService audit;

    public MroService(E10TestRepository tests, E10SpecimenRepository specimens, TestCore core, LabService lab, PathwayService pathway,
                      RtdService rtd, SubstanceSupport support, AuditService audit) {
        this.tests = tests;
        this.specimens = specimens;
        this.core = core;
        this.lab = lab;
        this.pathway = pathway;
        this.rtd = rtd;
        this.support = support;
        this.audit = audit;
    }

    /** Split-specimen result as stored (encrypted JSON). */
    public record SplitPayload(String labRef, String validity, String receivedAt, List<LabResult> results) {
    }

    @Transactional(readOnly = true)
    public List<MroQueueItem> queue(CurrentUser user) {
        support.requireMro(user, "DA_MRO_QUEUE_READ");
        List<MroQueueItem> out = tests.findByStatusInOrderByDueAtAsc(List.of(TestStatus.MRO_REVIEW.name())).stream()
                .sorted(Comparator.comparing(DaTest::getUpdatedAt))
                .map(this::item).toList();
        out.forEach(i -> audit.record("DA_RESULT_READ", "DA_TEST", i.testId().toString(), AuditService.Outcome.SUCCESS, "mro-queue"));
        return out;
    }

    private MroQueueItem item(DaTest t) {
        List<Specimen> sp = specimens.findByTestIdOrderByCollectedAtAsc(t.getId());
        String specimenId = sp.stream().filter(s -> "PRIMARY".equals(s.getKind())).sorted(Comparator.comparing(s -> "BREATH".equals(s.getSpecimenType())))
                .map(Specimen::getSpecimenId).findFirst().orElse(null);
        SplitPayload split = t.getSplitResults() == null ? null : support.read(t.getSplitResults(), SPLIT);
        return new MroQueueItem(t.getId(), t.getRef(), t.getEmployeeId(), support.name(t.getEmployeeId()), t.getReason(), specimenId,
                t.getLabValidity(), results(t), t.getLabReceivedAt() != null ? t.getLabReceivedAt() : t.getAlcoholCollectedAt(), t.getBreathResult(),
                t.getRefusalReason(), t.getRefusalObservations(), split == null ? null : split.results(), t.getSplitRequestedAt(),
                SubstanceSupport.split(t.getPanels()));
    }

    private List<LabResult> results(DaTest t) {
        return t.getResults() == null ? List.of() : support.read(t.getResults(), RESULTS);
    }

    public record DecisionCommand(String verdict, Boolean prescriptionVerified, String interviewNote, Boolean splitSpecimenRequested) {
    }

    @Transactional
    public MroDecisionView decide(CurrentUser user, UUID testId, DecisionCommand c) {
        support.requireMro(user, "DA_MRO_DECISION");
        DaTest t = core.load(testId);
        Verdict verdict = Verdict.valueOf(c.verdict());
        boolean split = Boolean.TRUE.equals(c.splitSpecimenRequested());
        if (split && verdict != Verdict.POSITIVE && verdict != Verdict.POSITIVE_DILUTE) {
            throw SubstanceSupport.unprocessable("split_requires_positive", "A split specimen is requested on a positive verdict");
        }
        SplitPayload splitResult = t.getSplitResults() == null ? null : support.read(t.getSplitResults(), SPLIT);
        List<LabResult> results = splitResult != null ? splitResult.results() : results(t);
        String validityName = splitResult != null ? splitResult.validity() : t.getLabValidity();
        boolean breathPositive = breathPositive(t.getBreathResult());
        boolean anyNonNegative = breathPositive || results.stream().anyMatch(r -> Outcome.NON_NEGATIVE.name().equals(r.outcome()));
        MroStateMachine.Facts facts = new MroStateMachine.Facts(TestStatus.valueOf(t.getStatus()), validityName == null ? null : Validity.valueOf(validityName),
                anyNonNegative, t.getRefusalReason() != null, t.getResults() != null || t.getBreathResult() != null, c.prescriptionVerified());
        TestStatus target;
        try {
            target = MroStateMachine.decide(facts, verdict);
        } catch (MroStateMachine.RuleViolation v) {
            throw new ApiException(v.conflict() ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT, v.code(), v.getMessage());
        }
        boolean previouslyPositive = t.getVerdict() != null && POSITIVE_VERDICTS.contains(Verdict.valueOf(t.getVerdict())) && t.getCaseId() != null;
        Instant now = support.now();
        t.setVerdict(verdict.name());
        t.setPrescriptionVerified(c.prescriptionVerified());
        if (c.interviewNote() != null && !c.interviewNote().isBlank()) {
            t.setInterviewNote(c.interviewNote().strip());
        }
        t.setMroUsername(user.username());
        t.setMroDecidedAt(now);
        core.transition(t, target);
        audit.record("DA_MRO_DECISION", "DA_TEST", t.getId().toString(), AuditService.Outcome.SUCCESS, verdict.name());

        UUID planId = null;
        switch (target) {
            case VERIFIED_POSITIVE, REFUSAL -> {
                RtdPlan plan = pathway.positive(t, user.username());
                planId = plan.getId();
            }
            case VERIFIED_NEGATIVE -> {
                if (Reason.RETURN_TO_DUTY.name().equals(t.getReason())) {
                    rtd.afterReturn(t, user.username());
                } else {
                    pathway.negative(t, user.username());
                }
            }
            case CANCELLED -> {
                if (previouslyPositive) {
                    pathway.reverse(t, user.username());
                }
            }
            default -> {
            }
        }
        if (split) {
            lab.orderSplit(user, t.getId());
        }
        return new MroDecisionView(t.getId(), t.getRef(), t.getStatus(), t.getVerdict(), now, t.getCaseId(), planId, split);
    }

    @Transactional
    public MroDecisionView splitRequest(CurrentUser user, UUID testId) {
        support.requireMro(user, "DA_SPLIT_REQUEST");
        lab.orderSplit(user, testId);
        DaTest t = core.load(testId);
        return new MroDecisionView(t.getId(), t.getRef(), t.getStatus(), t.getVerdict(), t.getMroDecidedAt(), t.getCaseId(), null, true);
    }

    boolean breathPositive(String breathResult) {
        if (breathResult == null) {
            return false;
        }
        try {
            BigDecimal cutoff = new BigDecimal(support.config("substance.breath-alcohol-cutoff", "0.02"));
            return new BigDecimal(breathResult).compareTo(cutoff) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
