package com.wellnessz.e10.mro;

import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.SubstanceEnums.Validity;
import com.wellnessz.e10.model.SubstanceEnums.Verdict;

/**
 * MRO verification rules (E10-F03). A verdict is only possible in {@code MRO_REVIEW}:
 * <ul>
 *   <li>NEGATIVE – all results negative (or a negative breath reading) on a valid / dilute specimen → VERIFIED_NEGATIVE</li>
 *   <li>NEGATIVE_LEGITIMATE_PRESCRIPTION – non-negative result explained by a verified prescription → VERIFIED_NEGATIVE</li>
 *   <li>POSITIVE / POSITIVE_DILUTE – at least one non-negative result (dilute: specimen validity DILUTE) → VERIFIED_POSITIVE</li>
 *   <li>REFUSAL_ADULTERATED / REFUSAL_SUBSTITUTED – laboratory validity ADULTERATED / SUBSTITUTED, or a refusal recorded by
 *       the collector → REFUSAL</li>
 *   <li>CANCELLED – e.g. INVALID specimen or a documented legitimate no-show → CANCELLED</li>
 * </ul>
 */
public final class MroStateMachine {

    private MroStateMachine() {
    }

    /** Facts the MRO decides on. {@code validity} is null when there is no laboratory result (breath only / refusal). */
    public record Facts(TestStatus status, Validity validity, boolean anyNonNegative, boolean refusalRecorded, boolean hasResults,
                        Boolean prescriptionVerified) {
    }

    /** A rule violation: {@code code} becomes the 409 / 422 problem code. */
    public static final class RuleViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final boolean conflict;

        RuleViolation(String code, String message, boolean conflict) {
            super(message);
            this.code = code;
            this.conflict = conflict;
        }

        public String code() {
            return code;
        }

        public boolean conflict() {
            return conflict;
        }
    }

    public static TestStatus decide(Facts f, Verdict verdict) {
        if (f.status() != TestStatus.MRO_REVIEW) {
            throw new RuleViolation("invalid_state", "A verdict can only be recorded in MRO_REVIEW (current " + f.status() + ")", true);
        }
        boolean refusalValidity = f.validity() == Validity.ADULTERATED || f.validity() == Validity.SUBSTITUTED;
        return switch (verdict) {
            case NEGATIVE -> {
                require(!f.refusalRecorded() && f.hasResults(), "results_required", "A negative verdict needs laboratory or breath results");
                require(!f.anyNonNegative(), "non_negative_result", "A non-negative result cannot be verified as NEGATIVE");
                require(!refusalValidity && f.validity() != Validity.INVALID, "invalid_specimen", "The specimen is not valid for a negative verdict");
                yield TestStatus.VERIFIED_NEGATIVE;
            }
            case NEGATIVE_LEGITIMATE_PRESCRIPTION -> {
                require(f.anyNonNegative(), "no_non_negative_result", "There is no non-negative result to explain");
                require(Boolean.TRUE.equals(f.prescriptionVerified()), "prescription_not_verified", "The prescription must be verified");
                yield TestStatus.VERIFIED_NEGATIVE;
            }
            case POSITIVE -> {
                require(f.anyNonNegative(), "no_non_negative_result", "A positive verdict needs a non-negative result");
                yield TestStatus.VERIFIED_POSITIVE;
            }
            case POSITIVE_DILUTE -> {
                require(f.anyNonNegative(), "no_non_negative_result", "A positive verdict needs a non-negative result");
                require(f.validity() == Validity.DILUTE, "not_dilute", "The specimen was not reported dilute");
                yield TestStatus.VERIFIED_POSITIVE;
            }
            case REFUSAL_ADULTERATED -> {
                require(f.validity() == Validity.ADULTERATED || f.refusalRecorded(), "no_refusal_basis",
                        "Refusal needs an adulterated specimen or a refusal recorded at collection");
                yield TestStatus.REFUSAL;
            }
            case REFUSAL_SUBSTITUTED -> {
                require(f.validity() == Validity.SUBSTITUTED || f.refusalRecorded(), "no_refusal_basis",
                        "Refusal needs a substituted specimen or a refusal recorded at collection");
                yield TestStatus.REFUSAL;
            }
            case CANCELLED -> TestStatus.CANCELLED;
        };
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new RuleViolation(code, message, false);
        }
    }
}
