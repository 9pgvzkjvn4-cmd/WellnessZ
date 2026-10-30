package com.wellnessz.e09.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Small-cell suppression for E09 programme statistics (confidentiality rule 4, {@code wellnessz.analytics.min-group-size}):
 * a non-zero count below the minimum group size is never published, and a rate is withheld when either its population or
 * its numerator is a small cell.
 */
public final class Suppression {

    private Suppression() {
    }

    /** True when a count must be withheld: 0 &lt; count &lt; k. */
    public static boolean suppressed(long count, int minGroupSize) {
        return count > 0 && count < minGroupSize;
    }

    /** Published count, or {@code null} when suppressed. */
    public static Long publish(long count, int minGroupSize) {
        return suppressed(count, minGroupSize) ? null : count;
    }

    /** Rate per 100 people (one decimal), {@code null} when the population or the count is a small cell. */
    public static BigDecimal ratePer100(long count, long population, int minGroupSize) {
        if (population <= 0 || population < minGroupSize || suppressed(count, minGroupSize)) {
            return null;
        }
        return BigDecimal.valueOf(count * 100L).divide(BigDecimal.valueOf(population), 1, RoundingMode.HALF_UP);
    }
}
