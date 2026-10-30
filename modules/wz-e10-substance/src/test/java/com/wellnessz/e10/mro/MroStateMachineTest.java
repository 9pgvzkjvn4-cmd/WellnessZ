package com.wellnessz.e10.mro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.SubstanceEnums.Validity;
import com.wellnessz.e10.model.SubstanceEnums.Verdict;

class MroStateMachineTest {

    static MroStateMachine.Facts facts(Validity validity, boolean nonNegative, boolean refusal, boolean results, Boolean prescription) {
        return new MroStateMachine.Facts(TestStatus.MRO_REVIEW, validity, nonNegative, refusal, results, prescription);
    }

    @Test
    void negativeResultsVerifyNegative() {
        assertThat(MroStateMachine.decide(facts(Validity.VALID, false, false, true, null), Verdict.NEGATIVE)).isEqualTo(TestStatus.VERIFIED_NEGATIVE);
        assertThat(MroStateMachine.decide(facts(Validity.DILUTE, false, false, true, null), Verdict.NEGATIVE)).isEqualTo(TestStatus.VERIFIED_NEGATIVE);
        assertThat(MroStateMachine.decide(facts(null, false, false, true, null), Verdict.NEGATIVE)).isEqualTo(TestStatus.VERIFIED_NEGATIVE); // breath only
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, true, false, true, null), Verdict.NEGATIVE))
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.INVALID, false, false, true, null), Verdict.NEGATIVE))
                .isInstanceOf(MroStateMachine.RuleViolation.class);
        assertThatThrownBy(() -> MroStateMachine.decide(facts(null, false, true, false, null), Verdict.NEGATIVE))
                .isInstanceOf(MroStateMachine.RuleViolation.class);
    }

    @Test
    void prescriptionExplainsANonNegativeOnlyWhenVerified() {
        assertThat(MroStateMachine.decide(facts(Validity.VALID, true, false, true, true), Verdict.NEGATIVE_LEGITIMATE_PRESCRIPTION))
                .isEqualTo(TestStatus.VERIFIED_NEGATIVE);
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, true, false, true, false), Verdict.NEGATIVE_LEGITIMATE_PRESCRIPTION))
                .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).code()).isEqualTo("prescription_not_verified"));
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, false, false, true, true), Verdict.NEGATIVE_LEGITIMATE_PRESCRIPTION))
                .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).code()).isEqualTo("no_non_negative_result"));
    }

    @Test
    void positiveNeedsANonNegativeResultAndDiluteNeedsADiluteSpecimen() {
        assertThat(MroStateMachine.decide(facts(Validity.VALID, true, false, true, null), Verdict.POSITIVE)).isEqualTo(TestStatus.VERIFIED_POSITIVE);
        assertThat(MroStateMachine.decide(facts(Validity.DILUTE, true, false, true, null), Verdict.POSITIVE_DILUTE)).isEqualTo(TestStatus.VERIFIED_POSITIVE);
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, true, false, true, null), Verdict.POSITIVE_DILUTE))
                .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).code()).isEqualTo("not_dilute"));
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, false, false, true, null), Verdict.POSITIVE))
                .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).conflict()).isFalse());
    }

    @Test
    void refusalNeedsAdulterationSubstitutionOrARecordedRefusal() {
        assertThat(MroStateMachine.decide(facts(Validity.ADULTERATED, false, false, true, null), Verdict.REFUSAL_ADULTERATED)).isEqualTo(TestStatus.REFUSAL);
        assertThat(MroStateMachine.decide(facts(Validity.SUBSTITUTED, false, false, true, null), Verdict.REFUSAL_SUBSTITUTED)).isEqualTo(TestStatus.REFUSAL);
        assertThat(MroStateMachine.decide(facts(null, false, true, false, null), Verdict.REFUSAL_ADULTERATED)).isEqualTo(TestStatus.REFUSAL);
        assertThatThrownBy(() -> MroStateMachine.decide(facts(Validity.VALID, false, false, true, null), Verdict.REFUSAL_SUBSTITUTED))
                .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).code()).isEqualTo("no_refusal_basis"));
        assertThat(MroStateMachine.decide(facts(Validity.INVALID, false, false, true, null), Verdict.CANCELLED)).isEqualTo(TestStatus.CANCELLED);
    }

    @Test
    void verdictsOnlyInMroReview() {
        for (TestStatus s : TestStatus.values()) {
            if (s == TestStatus.MRO_REVIEW) {
                continue;
            }
            MroStateMachine.Facts f = new MroStateMachine.Facts(s, Validity.VALID, true, false, true, null);
            assertThatThrownBy(() -> MroStateMachine.decide(f, Verdict.POSITIVE))
                    .satisfies(e -> assertThat(((MroStateMachine.RuleViolation) e).conflict()).isTrue());
        }
    }
}
