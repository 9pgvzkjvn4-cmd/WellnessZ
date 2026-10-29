package com.wellnessz.casekernel;

import java.time.LocalDate;
import java.util.List;

import com.wellnessz.casekernel.model.FitnessStatus;

/** Optional details of a fitness decision carried on the crew roster feed (validity and operational limitation codes). */
public record FitnessFeed(LocalDate validFrom, LocalDate validUntil, LocalDate reviewDate, List<String> operationalLimitations) {

    public static final FitnessFeed NONE = new FitnessFeed(null, null, null, List.of());

    public FitnessFeed {
        operationalLimitations = operationalLimitations == null ? List.of() : List.copyOf(operationalLimitations);
    }

    /** Roster-status vocabulary for crew scheduling: FIT, LIMITED, UNFIT or PENDING. */
    public static String rosterStatus(FitnessStatus f) {
        return switch (f) {
            case FIT -> "FIT";
            case FIT_WITH_RESTRICTIONS -> "LIMITED";
            case TEMPORARILY_UNFIT, PERMANENTLY_UNFIT -> "UNFIT";
            default -> "PENDING";
        };
    }
}
