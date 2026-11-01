package com.wellnessz.e03.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e03.results.DeviceRules;

/** Increment 6: the E03.RESULT routing adapter's field mapping and the device-rule evaluators it relies on. */
class DeviceEngineAdapterTest {

    @Test
    void boundFieldsBecomeTheNestedMeasurementMap() {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("measurements.left.500", new BigDecimal("10"));
        f.put("measurements.left.4000", new BigDecimal("20.0"));
        f.put("measurements.fev1L", new BigDecimal("3.85"));
        f.put("measurements.ageCorrection", Map.of("2000", 8));
        Map<String, Object> m = ResultDeviceSink.measurements(f);
        assertThat(m.get("left")).isEqualTo(Map.of("500", 10, "4000", 20));
        assertThat(m.get("fev1L")).isEqualTo(3.85);
        assertThat(m.get("ageCorrection")).isEqualTo(Map.of("2000", 8));
        assertThat(ResultDeviceSink.orderId("ORD:6f3b2c1e-9d1a-4e2f-8b31-31e31e31e310")).isNotNull();
        assertThat(ResultDeviceSink.orderId("APT:x")).isNull();
    }

    @Test
    void ecgDeviceTextIsClassifiedForPhysicianReview() {
        assertThat(ResultDeviceSink.ecgClass("Normal sinus rhythm Normal ECG")).isEqualTo("NORMAL");
        assertThat(ResultDeviceSink.ecgClass("Sinus rhythm (device, unverified)")).isEqualTo("NORMAL");
        assertThat(ResultDeviceSink.ecgClass("Borderline ECG")).isEqualTo("BORDERLINE");
        assertThat(ResultDeviceSink.ecgClass("Abnormal ECG – possible infarct")).isEqualTo("ABNORMAL");
        assertThat(ResultDeviceSink.ecgClass("Prolonged QT")).isEqualTo("ABNORMAL");
    }

    @Test
    void vitalsPocFitTestAndOtherCategories() {
        assertThat(DeviceRules.vitals(Map.of("systolic", 124, "diastolic", 78, "spo2", 98)).flag()).isEqualTo("NORMAL");
        assertThat(DeviceRules.vitals(Map.of("systolic", 152, "diastolic", 88)).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.vitals(Map.of("spo2", 91)).flag()).isEqualTo("ABNORMAL");
        assertThatThrownBy(() -> DeviceRules.vitals(Map.of("systolic", 400))).isInstanceOf(ApiException.class);
        assertThat(DeviceRules.poc(Map.of("hba1c", 6.8, "interpretations", Map.of("hba1c", "H"))).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.poc(Map.of("hba1c", 5.4)).flag()).isEqualTo("NORMAL");
        assertThat(DeviceRules.fitTest(Map.of("overallFitFactor", 88, "pass", false)).flag()).isEqualTo("ABNORMAL");
        assertThat(DeviceRules.fitTest(Map.of("overallFitFactor", 245, "pass", true)).value()).isEqualTo("Overall fit factor 245 (pass)");
        assertThat(DeviceRules.other("BODY_COMPOSITION", Map.of("weightKg", 78.4)).code().code()).isEqualTo("29463-7");
    }

    @Test
    void stsUsesTheEngineAgeCorrectionWhenBothAudiogramsCarryIt() {
        Map<String, Object> base = Map.of("left", Map.of("2000", 10, "3000", 10, "4000", 15), "right", Map.of("2000", 10, "3000", 10, "4000", 15),
                "ageCorrection", Map.of("2000", 4, "3000", 6, "4000", 9));
        Map<String, Object> now = Map.of("left", Map.of("2000", 20, "3000", 25, "4000", 30), "right", Map.of("2000", 10, "3000", 10, "4000", 15),
                "ageCorrection", Map.of("2000", 9, "3000", 16, "4000", 22));
        DeviceRules.Evaluation corrected = DeviceRules.audiometry(now, base, "b1");
        assertThat(corrected.flag()).isEqualTo("NORMAL");                  // 13.3 dB raw − 9.3 dB age correction
        assertThat(corrected.detail()).containsKey("ageCorrectionDb");
        Map<String, Object> nowNoAge = Map.of("left", Map.of("2000", 20, "3000", 25, "4000", 30), "right", Map.of("2000", 10, "3000", 10, "4000", 15));
        assertThat(DeviceRules.audiometry(nowNoAge, base, "b1").flag()).isEqualTo("ABNORMAL");
    }
}
