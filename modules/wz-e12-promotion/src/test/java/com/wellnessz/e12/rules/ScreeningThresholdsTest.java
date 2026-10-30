package com.wellnessz.e12.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ScreeningThresholdsTest {

    static final Map<String, ScreeningThresholds.Threshold> DEFAULTS = ScreeningThresholds.resolve(m -> Optional.empty());

    static Map<String, BigDecimal> measures(Object... kv) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], new BigDecimal(kv[i + 1].toString()));
        }
        return m;
    }

    @Test
    void normalBiometricsOpenNoFollowUp() {
        var flags = ScreeningThresholds.evaluate(measures("systolic", 128, "diastolic", 82, "bmi", "24.9", "glucoseMmol", "5.4",
                "cholesterolMmol", "5.1", "waistCm", 88), DEFAULTS);
        assertThat(flags).isEmpty();
        assertThat(ScreeningThresholds.outcome(flags)).isEqualTo("NORMAL");
    }

    @Test
    void highBloodPressureIsAbnormalAtTheBoundary() {
        assertThat(ScreeningThresholds.evaluate(measures("systolic", 140, "diastolic", 85), DEFAULTS)).containsExactly("SYSTOLIC_HIGH");
        assertThat(ScreeningThresholds.evaluate(measures("systolic", "139.9", "diastolic", 90), DEFAULTS)).containsExactly("DIASTOLIC_HIGH");
        assertThat(ScreeningThresholds.outcome(ScreeningThresholds.evaluate(measures("systolic", 162, "diastolic", 101), DEFAULTS)))
                .isEqualTo("ABNORMAL");
    }

    @Test
    void lowWellbeingScoreUsesALowerBound() {
        assertThat(ScreeningThresholds.evaluate(measures("wellbeingScore", 40), DEFAULTS)).containsExactly("WELLBEING_SCORE_LOW");
        assertThat(ScreeningThresholds.evaluate(measures("wellbeingScore", 41), DEFAULTS)).isEmpty();
        assertThat(ScreeningThresholds.evaluate(measures("mskScore", 7, "glucoseMmol", "7.0", "cholesterolMmol", "6.4"), DEFAULTS))
                .containsExactly("GLUCOSE_HIGH", "MSK_SCORE_HIGH");
    }

    @Test
    void configuredThresholdOverridesDefaultAndMalformedFallsBack() {
        var t = ScreeningThresholds.resolve(m -> switch (m) {
            case "systolic" -> Optional.of(">150");
            case "bmi" -> Optional.of("abc; drop table");
            default -> Optional.empty();
        });
        assertThat(t.get("systolic").expression()).isEqualTo(">150");
        assertThat(t.get("bmi").expression()).isEqualTo(">=30");
        assertThat(ScreeningThresholds.evaluate(measures("systolic", 150), t)).isEmpty();
        assertThat(ScreeningThresholds.evaluate(measures("systolic", 151), t)).containsExactly("SYSTOLIC_HIGH");
    }

    @Test
    void parseRejectsGarbage() {
        assertThat(ScreeningThresholds.parse("bmi", "30")).isEmpty();
        assertThat(ScreeningThresholds.parse("bmi", "=>30")).isEmpty();
        assertThat(ScreeningThresholds.parse("bmi", " <= 18.5 ")).map(ScreeningThresholds.Threshold::flag).contains("BMI_LOW");
    }
}
