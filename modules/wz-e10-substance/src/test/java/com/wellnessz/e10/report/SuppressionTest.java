package com.wellnessz.e10.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.wellnessz.e10.support.TestingViews;

class SuppressionTest {

    @Test
    void smallCellsAreSuppressedWithoutAnyCounts() {
        Suppression.Cell small = Suppression.cell("FOR_CAUSE", "AIRSIDE_DRIVER", 1, 1, 0, 5);
        assertThat(small.suppressed()).isTrue();
        assertThat(small.tests()).isNull();
        assertThat(small.verifiedPositive()).isNull();
        assertThat(small.refusals()).isNull();
        assertThat(small.positiveRatePct()).isNull();
        assertThat(Suppression.cell("RANDOM", "FLIGHT_CREW", 4, 0, 0, 5).suppressed()).isTrue();
    }

    @Test
    void cellsAtTheThresholdAreShownWithTheRate() {
        Suppression.Cell c = Suppression.cell("RANDOM", "FLIGHT_CREW", 8, 1, 1, 5);
        assertThat(c.suppressed()).isFalse();
        assertThat(c.tests()).isEqualTo(8);
        assertThat(c.positiveRatePct()).isEqualByComparingTo("12.5");
        assertThat(Suppression.cell("RANDOM", "CABIN_CREW", 5, 0, 0, 5).suppressed()).isFalse();
        assertThat(Suppression.suppress(0, 5)).isFalse();
        assertThat(Suppression.ratePct(1, 3)).isEqualByComparingTo("33.3");
        assertThat(Suppression.ratePct(1, 0)).isNull();
    }

    @Test
    void outcomeIsNeverVisibleOutsideTheMro() {
        assertThat(TestingViews.publicStatus("VERIFIED_POSITIVE")).isEqualTo("COMPLETED");
        assertThat(TestingViews.publicStatus("VERIFIED_NEGATIVE")).isEqualTo("COMPLETED");
        assertThat(TestingViews.publicStatus("REFUSAL")).isEqualTo("COMPLETED");
        assertThat(TestingViews.publicStatus("MRO_REVIEW")).isEqualTo("COLLECTED");
        assertThat(TestingViews.publicStatus("DISPATCHED")).isEqualTo("DISPATCHED");
        assertThat(TestingViews.publicStatus("WINDOW_MISSED")).isEqualTo("CLOSED");
    }
}
