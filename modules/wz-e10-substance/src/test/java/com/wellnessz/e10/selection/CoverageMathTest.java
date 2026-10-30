package com.wellnessz.e10.selection;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CoverageMathTest {

    @Test
    void annualTargetIsDrugPlusAlcoholRateOfThePool() {
        assertThat(CoverageMath.annualTarget(100, new BigDecimal("25"), new BigDecimal("10"))).isEqualTo(35);
        assertThat(CoverageMath.annualTarget(7, new BigDecimal("25"), new BigDecimal("10"))).isEqualTo(2 + 1); // ceil(1.75) + ceil(0.7)
        assertThat(CoverageMath.annualTarget(0, new BigDecimal("25"), new BigDecimal("10"))).isZero();
    }

    @Test
    void coverageIsCompletedOverTargetWithOneDecimal() {
        assertThat(CoverageMath.coveragePct(7, 35)).isEqualByComparingTo("20.0");
        assertThat(CoverageMath.coveragePct(1, 3)).isEqualByComparingTo("33.3");
        assertThat(CoverageMath.coveragePct(40, 35)).isEqualByComparingTo("114.3");
        assertThat(CoverageMath.coveragePct(0, 0)).isEqualByComparingTo("100.0");
    }

    @Test
    void behindPlanComparesWithTheProRataShare() {
        assertThat(CoverageMath.expectedToDate(36, 6)).isEqualTo(18);
        assertThat(CoverageMath.behindPlan(17, 36, 6)).isTrue();
        assertThat(CoverageMath.behindPlan(18, 36, 6)).isFalse();
        assertThat(CoverageMath.behindPlan(0, 36, 0)).isFalse();      // future year
        assertThat(CoverageMath.behindPlan(35, 36, 12)).isTrue();     // past year: full target
        assertThat(CoverageMath.expectedToDate(5, 1)).isZero();       // rounded down early in the year
    }
}
