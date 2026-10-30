package com.wellnessz.e12.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Small-cell suppression and participation / outcome maths for E12 analytics (contract rule 4, E12-HLR-05). Counts below the
 * minimum group size {@code k} are never published; when exactly one cell of a series is hidden the next-smallest cell is
 * hidden too, so it cannot be recovered by subtraction. Pure – unit tested.
 */
public final class Suppression {

    private Suppression() {
    }

    /** Primary + complementary suppression of a count series; {@code true} = hide. */
    public static boolean[] counts(List<Long> counts, int k) {
        boolean[] hide = new boolean[counts.size()];
        int primary = 0;
        for (int i = 0; i < counts.size(); i++) {
            long c = counts.get(i);
            if (c > 0 && c < k) {
                hide[i] = true;
                primary++;
            }
        }
        if (primary == 1) {
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < counts.size(); i++) {
                if (!hide[i] && counts.get(i) >= k) {
                    order.add(i);
                }
            }
            order.stream().min(Comparator.comparingLong(counts::get)).ifPresent(i -> hide[i] = true);
        }
        return hide;
    }

    /**
     * A participation rate is published only when the group is at least {@code k} and the number of participants is either
     * zero or at least {@code k} (a handful of participants in a unit could be recognised).
     */
    public static boolean rate(long invited, long participated, int k) {
        return invited < k || (participated > 0 && participated < k);
    }

    /** Percentage with one decimal (HALF_UP), {@code null} for an empty denominator. */
    public static BigDecimal pct(long numerator, long denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
    }

    /** Mean with two decimals, {@code null} for no values. */
    public static BigDecimal mean(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }

    /** current − baseline, {@code null} when either side is missing. */
    public static BigDecimal delta(BigDecimal baseline, BigDecimal current) {
        return baseline == null || current == null ? null : current.subtract(baseline);
    }
}
