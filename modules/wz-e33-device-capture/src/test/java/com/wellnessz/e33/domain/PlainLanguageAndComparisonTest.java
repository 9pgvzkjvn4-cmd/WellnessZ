package com.wellnessz.e33.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlainLanguageAndComparisonTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void bloodPressureCategoriesFollowEsh2018() {
        assertThat(PlainLanguage.bpCategory(d("118"), d("76"))).isEqualTo("optimal");
        assertThat(PlainLanguage.bpCategory(d("125"), d("82"))).isEqualTo("normal");
        assertThat(PlainLanguage.bpCategory(d("134"), d("80"))).isEqualTo("high-normal");
        assertThat(PlainLanguage.bpCategory(d("128"), d("91"))).isEqualTo("raised");
    }

    @Test
    void bmiBandsAndComputation() {
        assertThat(PlainLanguage.bmi(d("74.5"), d("172"))).isEqualByComparingTo("25.2");
        assertThat(PlainLanguage.bmiBand(d("18.4"))).isEqualTo("below");
        assertThat(PlainLanguage.bmiBand(d("24.9"))).isEqualTo("healthy");
        assertThat(PlainLanguage.bmiBand(d("29.9"))).isEqualTo("above");
        assertThat(PlainLanguage.bmiBand(d("30"))).isEqualTo("well-above");
        assertThat(PlainLanguage.bmi(null, d("170"))).isNull();
    }

    @Test
    void summaryInPreferredLanguageWithoutDiagnosis() {
        PlainLanguage.Summary en = PlainLanguage.summarise("VITALS", Map.of("systolic", d("142"), "diastolic", d("88")), LocalDate.of(2026, 10, 31), "en");
        assertThat(en.language()).isEqualTo("en");
        assertThat(en.text()).contains("142/88 mmHg").contains("raised").contains("not a diagnosis");
        assertThat(en.text().toLowerCase()).doesNotContain("hypertension");
        assertThat(en.educationLink()).isEqualTo("/health-checks/learn/blood-pressure");
        PlainLanguage.Summary fr = PlainLanguage.summarise("BODY_COMPOSITION", Map.of("weightKg", d("74.5"), "heightCm", d("172")),
                LocalDate.of(2026, 10, 31), "fr");
        assertThat(fr.language()).isEqualTo("fr");
        assertThat(fr.text()).contains("IMC").contains("25.2");
        PlainLanguage.Summary ar = PlainLanguage.summarise("AUDIOMETRY", Map.of(), LocalDate.of(2026, 10, 31), "ar");
        assertThat(ar.text()).contains("اختبار السمع");
        assertThat(PlainLanguage.summarise("SPIROMETRY", Map.of(), LocalDate.of(2026, 10, 31), "de").language()).isEqualTo("en");
    }

    @Test
    void comparisonHasBaselinePreviousTrendAndThresholds() {
        List<Comparison.Point> points = List.of(
                new Comparison.Point(Instant.parse("2026-04-01T09:00:00Z"), Map.of("systolic", d("126")), null),
                new Comparison.Point(Instant.parse("2025-10-01T09:00:00Z"), Map.of("systolic", d("120")), null),
                new Comparison.Point(Instant.parse("2026-10-01T09:00:00Z"), Map.of("systolic", d("132")), "FLAGGED"));
        Comparison.Summary s = Comparison.build("VITALS", points);
        assertThat(s.baseline().values().get("systolic")).isEqualByComparingTo("120");
        assertThat(s.previous()).hasSize(3);
        assertThat(s.previous().get(0).values().get("systolic")).isEqualByComparingTo("132");
        assertThat(s.previous().get(0).flag()).isEqualTo("FLAGGED");
        assertThat(s.trend()).extracting(Comparison.TrendPoint::date).containsExactly(LocalDate.of(2025, 10, 1), LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 10, 1));
        assertThat(s.thresholds()).extracting(Comparison.Threshold::key).contains("systolic", "diastolic");
        assertThat(Comparison.build("SPIROMETRY", List.of()).baseline()).isNull();
    }
}
