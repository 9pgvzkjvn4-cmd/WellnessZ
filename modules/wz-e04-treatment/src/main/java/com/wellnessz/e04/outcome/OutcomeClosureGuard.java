package com.wellnessz.e04.outcome;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.CaseStatus;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseTransitionGuard;
import com.wellnessz.e04.model.TreatmentOutcomeRepository;
import com.wellnessz.e04.model.TreatmentPlanRepository;
import com.wellnessz.e04.model.TreatmentReferralRepository;
import com.wellnessz.e04.support.TreatmentSupport;

/** E04-F06: a case with a treatment plan or a referral cannot close without a CLOSURE outcome (422 {@code outcome_required}). */
@Component
public class OutcomeClosureGuard implements CaseTransitionGuard {

    private final TreatmentPlanRepository plans;
    private final TreatmentReferralRepository referrals;
    private final TreatmentOutcomeRepository outcomes;

    public OutcomeClosureGuard(TreatmentPlanRepository plans, TreatmentReferralRepository referrals, TreatmentOutcomeRepository outcomes) {
        this.plans = plans;
        this.referrals = referrals;
        this.outcomes = outcomes;
    }

    @Override
    public void check(OhCase c, CaseStatus next) {
        if (next == CaseStatus.CLOSED && (plans.existsByCaseId(c.getId()) || referrals.existsByCaseId(c.getId()))
                && !outcomes.existsByCaseIdAndMilestone(c.getId(), "CLOSURE")) {
            throw TreatmentSupport.unprocessable("outcome_required", "Record a CLOSURE outcome (pain, function, RTW) before closing this case");
        }
    }
}
