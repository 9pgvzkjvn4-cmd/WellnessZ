package com.wellnessz.e10.selection;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Random-testing coverage against the policy target (E10-BL-002): the annual target is {@code ceil(pool × drug rate%)} drug
 * tests plus {@code ceil(pool × alcohol rate%)} alcohol tests; coverage is completed / target; a role group is behind plan
 * when fewer tests are completed than the pro-rata share of the target for the months elapsed.
 */
public final class CoverageMath {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private CoverageMath() {
    }

    public static int annualTarget(int poolSize, BigDecimal drugRatePct, BigDecimal alcoholRatePct) {
        return ceilPct(poolSize, drugRatePct) + ceilPct(poolSize, alcoholRatePct);
    }

    static int ceilPct(int poolSize, BigDecimal ratePct) {
        if (poolSize <= 0 || ratePct == null || ratePct.signum() <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(poolSize).multiply(ratePct).divide(HUNDRED, 0, RoundingMode.CEILING).intValueExact();
    }

    /** Completed / target in percent with one decimal; a zero target counts as fully covered. */
    public static BigDecimal coveragePct(int completed, int target) {
        if (target <= 0) {
            return BigDecimal.valueOf(100).setScale(1, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(completed).multiply(HUNDRED).divide(BigDecimal.valueOf(target), 1, RoundingMode.HALF_UP);
    }

    /** Pro-rata expectation after {@code monthsElapsed} (1–12) months, rounded down. */
    public static int expectedToDate(int target, int monthsElapsed) {
        int m = Math.max(0, Math.min(12, monthsElapsed));
        return target * m / 12;
    }

    public static boolean behindPlan(int completed, int target, int monthsElapsed) {
        return completed < expectedToDate(target, monthsElapsed);
    }
}
