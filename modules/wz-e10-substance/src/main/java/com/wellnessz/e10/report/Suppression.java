package com.wellnessz.e10.report;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Small-cell suppression of programme statistics (confidentiality rule 4, {@code wellnessz.analytics.min-group-size}): a
 * cell with fewer than the minimum number of tests (but more than none) is reported without any counts.
 */
public final class Suppression {

    private Suppression() {
    }

    /** One statistics cell (reason × role group). Counts are null when suppressed. */
    public record Cell(String reason, String roleGroup, Integer tests, Integer verifiedPositive, Integer refusals, BigDecimal positiveRatePct,
                       boolean suppressed) {
    }

    public static boolean suppress(int tests, int minGroupSize) {
        return tests > 0 && tests < minGroupSize;
    }

    public static BigDecimal ratePct(int part, int whole) {
        if (whole <= 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    public static Cell cell(String reason, String roleGroup, int tests, int positives, int refusals, int minGroupSize) {
        if (suppress(tests, minGroupSize)) {
            return new Cell(reason, roleGroup, null, null, null, null, true);
        }
        return new Cell(reason, roleGroup, tests, positives, refusals, ratePct(positives, tests), false);
    }
}
