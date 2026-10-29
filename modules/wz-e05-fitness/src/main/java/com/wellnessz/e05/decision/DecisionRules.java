package com.wellnessz.e05.decision;

import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Set;

import com.wellnessz.casekernel.model.FitnessStatus;

/** Pure rules of the fitness decision module (E05-F01): outcome mapping, validity defaults and restriction status. */
public final class DecisionRules {

    public static final Set<String> OUTCOMES = Set.of("FIT", "FIT_WITH_LIMITATIONS", "TEMPORARILY_UNFIT", "PERMANENTLY_UNFIT", "PENDING");
    public static final String FINAL = "FINAL";
    public static final String AWAITING_SIGNOFF = "AWAITING_SIGNOFF";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String REJECTED = "REJECTED";
    /** Days before expiry at which a restriction becomes EXPIRING and alerts are sent (E05-BL-016). */
    public static final int EXPIRING_DAYS = 5;

    private DecisionRules() {
    }

    /**
     * Kernel fitness status for an outcome. PERMANENTLY_UNFIT only takes effect after CMO sign-off; until then the case
     * is UNDER_REVIEW (roster status PENDING).
     */
    public static FitnessStatus kernelStatus(String outcome, boolean signedOff) {
        return switch (outcome) {
            case "FIT" -> FitnessStatus.FIT;
            case "FIT_WITH_LIMITATIONS" -> FitnessStatus.FIT_WITH_RESTRICTIONS;
            case "TEMPORARILY_UNFIT" -> FitnessStatus.TEMPORARILY_UNFIT;
            case "PERMANENTLY_UNFIT" -> signedOff ? FitnessStatus.PERMANENTLY_UNFIT : FitnessStatus.UNDER_REVIEW;
            default -> FitnessStatus.UNDER_REVIEW;
        };
    }

    /** Age in whole years on {@code on}, or {@code null} when the date of birth is unknown (HRIS feed has none today). */
    public static Integer age(LocalDate dateOfBirth, LocalDate on) {
        return dateOfBirth == null ? null : Period.between(dateOfBirth, on).getYears();
    }

    /**
     * Default end of validity: FIT / FIT_WITH_LIMITATIONS use the standard's validity, the shorter over-40 validity
     * applying when the employee's age is known and above 40; other outcomes have no default validity.
     */
    public static LocalDate defaultValidUntil(String outcome, LocalDate validFrom, int validityMonths, Integer validityMonthsOver40,
                                              Integer age) {
        if (!"FIT".equals(outcome) && !"FIT_WITH_LIMITATIONS".equals(outcome)) {
            return null;
        }
        int months = age != null && age > 40 && validityMonthsOver40 != null ? validityMonthsOver40 : validityMonths;
        return months <= 0 ? null : validFrom.plusMonths(months).minusDays(1);
    }

    /** Restriction-until carried on the kernel case (status feeds): end of the limitation or unfitness period. */
    public static LocalDate kernelUntil(String outcome, LocalDate validUntil, LocalDate reviewDate) {
        return switch (outcome) {
            case "FIT_WITH_LIMITATIONS", "TEMPORARILY_UNFIT" -> validUntil != null ? validUntil : reviewDate;
            default -> null;
        };
    }

    /** Only operational limitation codes are disclosed to rostering (E05-F04). */
    public static List<String> operational(List<String> codes) {
        return codes == null ? List.of() : codes.stream().filter(LimitationCodes::isOperational).toList();
    }

    /** Effective restriction-instance status: ACTIVE becomes EXPIRING within 5 days of the end date. */
    public static String restrictionStatus(String stored, LocalDate validUntil, LocalDate today) {
        if (!"ACTIVE".equals(stored)) {
            return stored;
        }
        if (validUntil != null && validUntil.isBefore(today)) {
            return "LAPSED";
        }
        if (validUntil != null && !validUntil.isAfter(today.plusDays(EXPIRING_DAYS))) {
            return "EXPIRING";
        }
        return "ACTIVE";
    }
}
