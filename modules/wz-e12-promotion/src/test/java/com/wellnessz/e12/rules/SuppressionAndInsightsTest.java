package com.wellnessz.e12.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SuppressionAndInsightsTest {

    @Test
    void primaryAndComplementarySuppression() {
        assertThat(Suppression.counts(List.of(12L, 3L, 7L), 5)).containsExactly(false, true, true);
        assertThat(Suppression.counts(List.of(12L, 3L, 2L), 5)).containsExactly(false, true, true);
        assertThat(Suppression.counts(List.of(12L, 9L, 0L), 5)).containsExactly(false, false, false);
        assertThat(Suppression.counts(List.of(4L), 5)).containsExactly(true);
    }

    @Test
    void participationRateMaths() {
        assertThat(Suppression.pct(5, 7)).isEqualByComparingTo("71.4");
        assertThat(Suppression.pct(2, 3)).isEqualByComparingTo("66.7");
        assertThat(Suppression.pct(1, 0)).isNull();
        assertThat(Suppression.rate(4, 0, 5)).isTrue();   // group too small
        assertThat(Suppression.rate(20, 3, 5)).isTrue();  // a handful of participants could be recognised
        assertThat(Suppression.rate(20, 0, 5)).isFalse(); // nobody participated – nothing to disclose
        assertThat(Suppression.rate(20, 8, 5)).isFalse();
    }

    @Test
    void outcomeMaths() {
        BigDecimal b = Suppression.mean(List.of(new BigDecimal("140"), new BigDecimal("150"), new BigDecimal("131")));
        BigDecimal c = Suppression.mean(List.of(new BigDecimal("128"), new BigDecimal("135"), new BigDecimal("130")));
        assertThat(b).isEqualByComparingTo("140.33");
        assertThat(c).isEqualByComparingTo("131.00");
        assertThat(Suppression.delta(b, c)).isEqualByComparingTo("-9.33");
        assertThat(Suppression.delta(null, c)).isNull();
        assertThat(Suppression.mean(List.of())).isNull();
    }

    @Test
    void mskAndShiftClassification() {
        assertThat(InsightRules.isMsk("Lower back")).isTrue();
        assertThat(InsightRules.isMsk("Right shoulder")).isTrue();
        assertThat(InsightRules.isMsk("Left eye")).isFalse();
        assertThat(InsightRules.isMsk(null)).isFalse();
        assertThat(InsightRules.isNightShift("NIGHT")).isTrue();
        assertThat(InsightRules.isNightShift("EARLY")).isFalse();
    }

    @Test
    void insightsAreAggregatedPerUnitAndSuppressed() {
        List<InsightRules.Count> counts = List.of(
                new InsightRules.Count("GROUND", "MSK", "MSK_INJURY_CASES", 4),
                new InsightRules.Count("GROUND", "MSK", "MSK_INJURY_CASES", 3),
                new InsightRules.Count("CATERING", "MSK", "MSK_INJURY_CASES", 6),
                new InsightRules.Count("CARGO", "MSK", "MSK_INJURY_CASES", 1),
                new InsightRules.Count("ENG", "HEARING", "AUDIOMETRY_STS", 2),
                new InsightRules.Count("CORP", "METABOLIC", "ABNORMAL_BIOMETRIC_SCREENS", 0),
                new InsightRules.Count(null, "MSK", "MSK_INJURY_CASES", 50));
        List<InsightRules.Insight> out = InsightRules.publish(counts, "2025-10-30/2026-10-30", 5);
        assertThat(out).extracting(InsightRules.Insight::unitCode).containsExactly("ENG", "CARGO", "CATERING", "GROUND");
        InsightRules.Insight ground = out.stream().filter(i -> i.unitCode().equals("GROUND")).findFirst().orElseThrow();
        assertThat(ground.value()).isEqualTo(7L);
        assertThat(ground.suppressed()).isFalse();
        assertThat(ground.ref()).isEqualTo("MSK:GROUND:MSK_INJURY_CASES");
        // CARGO (1) is a small cell; CATERING (6) is the complementary cell
        assertThat(out.stream().filter(i -> i.unitCode().equals("CARGO")).findFirst().orElseThrow().value()).isNull();
        assertThat(out.stream().filter(i -> i.unitCode().equals("CATERING")).findFirst().orElseThrow().suppressed()).isTrue();
        assertThat(out.stream().filter(i -> i.unitCode().equals("ENG")).findFirst().orElseThrow().suppressed()).isTrue();
        assertThat(out).allSatisfy(i -> assertThat(i.suppressed() == (i.value() == null)).isTrue());
    }
}
