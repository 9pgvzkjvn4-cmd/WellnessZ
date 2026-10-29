package com.wellnessz.e03.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;

class DeviceRulesTest {

    static Map<String, Object> audiogram(int l2, int l3, int l4, int r2, int r3, int r4) {
        return Map.of("left", Map.of("500", 10, "1000", 10, "2000", l2, "3000", l3, "4000", l4),
                "right", Map.of("500", 10, "1000", 10, "2000", r2, "3000", r3, "4000", r4));
    }

    @Test
    void firstAudiogramBecomesTheBaseline() {
        DeviceRules.Evaluation e = DeviceRules.audiometry(audiogram(10, 15, 20, 10, 10, 10), null, null);
        assertThat(e.baseline()).isTrue();
        assertThat(e.flag()).isEqualTo("NORMAL");
        assertThat(e.code().code()).isEqualTo("28539-6");
    }

    @Test
    void standardThresholdShiftIsAnAverageChangeOfTenDbAtTwoThreeAndFourKhz() {
        Map<String, Object> baseline = audiogram(10, 15, 20, 10, 10, 10);
        // left: +10 average exactly -> STS; right: +9.3 -> no STS
        DeviceRules.Evaluation sts = DeviceRules.audiometry(audiogram(20, 25, 30, 20, 20, 18), baseline, "b1");
        assertThat(sts.flag()).isEqualTo("ABNORMAL");
        assertThat(sts.detail().get("sts")).isEqualTo(Map.of("left", true, "right", false));
        assertThat(sts.detail().get("baselineId")).isEqualTo("b1");
        DeviceRules.Evaluation stable = DeviceRules.audiometry(audiogram(15, 20, 25, 15, 15, 15), baseline, "b1");
        assertThat(stable.flag()).isEqualTo("NORMAL");
        assertThat(stable.baseline()).isFalse();
    }

    @Test
    void audiometryNeedsValidThresholdsAtTwoThreeAndFourKhz() {
        assertThatThrownBy(() -> DeviceRules.audiometry(Map.of("left", Map.of("2000", 10), "right", Map.of("2000", 10)), null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.audiometry(Map.of("left", Map.of("2000", 10, "3000", 10, "4000", 200),
                "right", Map.of("2000", 10, "3000", 10, "4000", 10)), null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.audiometry(Map.of("left", Map.of("1234", 10)), null, null)).isInstanceOf(ApiException.class);
    }

    @Test
    void spirometryRatioAndPercentPredicted() {
        assertThat(DeviceRules.spirometry(Map.of("fev1L", 2.5, "fvcL", 4.0)).flag()).isEqualTo("ABNORMAL"); // 0.625
        assertThat(DeviceRules.spirometry(Map.of("fev1L", 3.0, "fvcL", 4.0)).flag()).isEqualTo("NORMAL"); // 0.75
        assertThat(DeviceRules.spirometry(Map.of("fev1L", 3.0, "fvcL", 4.0, "fev1PredictedL", 4.0)).flag()).isEqualTo("ABNORMAL"); // 75 %
        assertThat(DeviceRules.spirometry(Map.of("fev1L", 3.3, "fvcL", 4.0, "fev1PredictedL", 4.0)).flag()).isEqualTo("NORMAL"); // 82.5 %
        assertThatThrownBy(() -> DeviceRules.spirometry(Map.of("fev1L", 3.0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.spirometry(Map.of("fev1L", -1, "fvcL", 3))).isInstanceOf(ApiException.class);
    }

    @Test
    void visionWorseThanSixTwelveOrFewerThanTwelveOfFifteenPlates() {
        assertThat(DeviceRules.vision(Map.of("binocular", "6/12")).flag()).isEqualTo("NORMAL");
        assertThat(DeviceRules.vision(Map.of("binocular", "6/18")).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.vision(Map.of("binocular", "6/6", "colourPlatesCorrect", 12, "colourPlatesTotal", 15)).flag()).isEqualTo("NORMAL");
        assertThat(DeviceRules.vision(Map.of("binocular", "6/6", "colourPlatesCorrect", 11, "colourPlatesTotal", 15)).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.acuity("20/40")).isEqualTo(0.5);
        assertThatThrownBy(() -> DeviceRules.vision(Map.of("binocular", "six/six"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> DeviceRules.vision(Map.of("binocular", "6/6", "colourPlatesCorrect", 16, "colourPlatesTotal", 15)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void ecgFollowsTheInterpretation() {
        assertThat(DeviceRules.ecg(Map.of("interpretation", "NORMAL", "heartRate", 64)).flag()).isEqualTo("NORMAL");
        assertThat(DeviceRules.ecg(Map.of("interpretation", "borderline")).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.ecg(Map.of("interpretation", "ABNORMAL", "qtcMs", 480)).value()).contains("QTc 480 ms");
        assertThatThrownBy(() -> DeviceRules.ecg(Map.of("interpretation", "UNKNOWN"))).isInstanceOf(ApiException.class);
    }
}
