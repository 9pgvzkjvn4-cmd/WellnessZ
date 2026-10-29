package com.wellnessz.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class SmallCellSuppressionTest {

    @Test
    void suppressesSmallCellsAndOneComplementaryCell() {
        var out = SmallCellSuppression.apply(List.of(
                new SmallCellSuppression.Cell("CABIN", "INTAKE", 2),
                new SmallCellSuppression.Cell("CABIN", "TRIAGE", 7),
                new SmallCellSuppression.Cell("CARGO", "INTAKE", 20)), 5);
        assertThat(out.get(0).suppressed()).isTrue();
        assertThat(out.get(0).count()).isNull();
        assertThat(out.get(1).suppressed()).isTrue(); // complementary: next smallest
        assertThat(out.get(2).count()).isEqualTo(20L);
    }

    @Test
    void noComplementaryWhenSeveralPrimary() {
        var out = SmallCellSuppression.apply(List.of(
                new SmallCellSuppression.Cell("A", "X", 1),
                new SmallCellSuppression.Cell("B", "X", 2),
                new SmallCellSuppression.Cell("C", "X", 9)), 5);
        assertThat(out).extracting(SmallCellSuppression.PublishedCell::suppressed).containsExactly(true, true, false);
    }
}
