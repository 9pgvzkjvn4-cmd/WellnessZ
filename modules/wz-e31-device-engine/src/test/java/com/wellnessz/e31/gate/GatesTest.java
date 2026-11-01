package com.wellnessz.e31.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.GateOutcome;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservation.QualityFlag;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.driver.DriverPackage;

/** Every quality gate of contract section 10. */
class GatesTest {

    static final Instant AT = Instant.parse("2026-10-30T10:00:00Z");

    /** Configurable environment. */
    static final class Env implements GateEnvironment {
        DeviceCalibrationCheck.Verdict verdict = DeviceCalibrationCheck.Verdict.VALID;
        String qc = "VALID";
        LocalDate lotExpiry;
        List<BigDecimal> history = List.of();
        boolean consent = true;
        boolean requireRegistered;

        @Override
        public List<DeviceCalibrationCheck.Status> calibration(String equipmentDeviceId, LocalDate on) {
            return List.of(new DeviceCalibrationCheck.Status(verdict, "CAL-1", LocalDate.of(2026, 10, 1)));
        }

        @Override
        public DeviceCatalogueLookup.QcStatus qc(String deviceInstanceId, LocalDate on, String lot) {
            return new DeviceCatalogueLookup.QcStatus(qc, "X", null, null, lot, lotExpiry);
        }

        @Override
        public List<BigDecimal> history(String employeeId, String category, String key, int limit) {
            return history;
        }

        @Override
        public boolean consent(String employeeId, String purpose) {
            return consent;
        }

        @Override
        public boolean requireRegisteredDevices() {
            return requireRegistered;
        }
    }

    static DeviceObservation.Component q(String key, String value) {
        return new DeviceObservation.Component(key, null, "L", key, "QUANTITY", new BigDecimal(value), null, "1", null, null, null);
    }

    static DeviceObservation.Component t(String key, String text) {
        return new DeviceObservation.Component(key, null, "L", key, "STRING", null, text, null, null, null, null);
    }

    DriverPackage pkg(String id) {
        return TestSupport.bundled().get(id, null).orElseThrow();
    }

    GateContext ctx(String pkg, DeviceCodes.Category cat, List<DeviceObservation.Component> comps, Env env, long skew,
                    DeviceCodes.CaptureMethod method, String equipment) {
        return new GateContext(pkg(pkg), cat, comps, "DEV-1", equipment, "MD-1", Map.of(), "E1001", AT, skew, 120, method, null, null, null, null, null,
                null, new BigDecimal("0.90"), true, env);
    }

    static GateOutcome worst(List<QualityFlag> flags) {
        return flags.stream().map(QualityFlag::outcome).max(Enum::compareTo).orElseThrow();
    }

    static List<String> codes(List<QualityFlag> flags) {
        return flags.stream().map(QualityFlag::code).toList();
    }

    @Test
    void calibrationBlocksExpiredAndOutOfServiceAndWarnsUnknown() {
        Env env = new Env();
        String p = "vitals.welchallyn.csm-pcd01";
        assertThat(worst(Gates.evaluate("CALIBRATION", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "EQ-1"))))
                .isEqualTo(GateOutcome.PASS);
        env.verdict = DeviceCalibrationCheck.Verdict.OUT_OF_CALIBRATION;
        List<QualityFlag> f = Gates.evaluate("CALIBRATION", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "EQ-1"));
        assertThat(f.get(0).outcome()).isEqualTo(GateOutcome.BLOCK);
        assertThat(f.get(0).code()).isEqualTo("device_out_of_calibration");
        assertThat(f.get(0).message()).startsWith("Expired calibration").contains("2026-10-01");
        env.verdict = DeviceCalibrationCheck.Verdict.OUT_OF_SERVICE;
        assertThat(codes(Gates.evaluate("CALIBRATION", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "EQ-1"))))
                .containsExactly("device_out_of_service");
        assertThat(worst(Gates.evaluate("CALIBRATION", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, null))))
                .isEqualTo(GateOutcome.WARN);
        env.requireRegistered = true;
        assertThat(worst(Gates.evaluate("CALIBRATION", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, null))))
                .isEqualTo(GateOutcome.BLOCK);
    }

    @Test
    void qcDailyLockoutAndQcLot() {
        Env env = new Env();
        String p = "spiro.ndd.easyone-connect-gdt";
        env.qc = "DUE";
        assertThat(codes(Gates.evaluate("QC_DAILY:SPIRO_SYRINGE_3L", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .containsExactly("qc_due");
        env.qc = "FAILED";
        assertThat(codes(Gates.evaluate("QC_DAILY", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .containsExactly("qc_failed");
        env.qc = "NOT_REQUIRED";
        assertThat(worst(Gates.evaluate("QC_DAILY", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .isEqualTo(GateOutcome.PASS);
        String poc = "poc.abbott.afinion2-poct1a";
        env.qc = "VALID";
        assertThat(codes(Gates.evaluate("QC_LOT", ctx(poc, DeviceCodes.Category.POC, List.of(), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .containsExactly("qc_lot_invalid");
        assertThat(worst(Gates.evaluate("QC_LOT", ctx(poc, DeviceCodes.Category.POC, List.of(t("qcLot", "AF-1")), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .isEqualTo(GateOutcome.PASS);
        env.lotExpiry = LocalDate.of(2026, 10, 1);
        assertThat(worst(Gates.evaluate("QC_LOT", ctx(poc, DeviceCodes.Category.POC, List.of(t("qcLot", "AF-1")), env, 0, DeviceCodes.CaptureMethod.DEVICE, "E"))))
                .isEqualTo(GateOutcome.BLOCK);
    }

    @Test
    void atsErsGradingFromDeviceAndFromTrials() {
        assertThat(Gates.gradeFromTrials(List.of(3.85, 3.80, 3.78), List.of(4.92, 4.88, 4.85))).isEqualTo("A");
        assertThat(Gates.gradeFromTrials(List.of(3.85, 3.67, 3.60), List.of(4.92, 4.90, 4.88))).isEqualTo("B");
        assertThat(Gates.gradeFromTrials(List.of(3.85, 3.62, 3.60), List.of(4.92, 4.90, 4.88))).isEqualTo("C");
        assertThat(Gates.gradeFromTrials(List.of(3.85, 3.50, 3.40), List.of(4.92, 4.90, 4.88))).isEqualTo("D");
        assertThat(Gates.gradeFromTrials(List.of(3.85, 3.80), List.of(4.92, 4.90))).isEqualTo("D");
        assertThat(Gates.gradeFromTrials(List.of(3.85), List.of(4.92))).isEqualTo("E");
        assertThat(Gates.gradeFromTrials(List.of(), List.of())).isEqualTo("F");
        Env env = new Env();
        String p = "spiro.ndd.easyone-connect-gdt";
        assertThat(codes(Gates.evaluate("ATS_ERS_GRADE:MIN=C", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(t("sessionGrade", "B")), env, 0, null, "E"))))
                .containsExactly("grade_B");
        assertThat(codes(Gates.evaluate("ATS_ERS_GRADE:MIN=C", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(t("sessionGrade", "D")), env, 0, null, "E"))))
                .containsExactly("grade_below_minimum");
        assertThat(worst(Gates.evaluate("ATS_ERS_GRADE", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(t("fev1Trials", "3.1")), env, 0, null, "E"))))
                .isEqualTo(GateOutcome.WARN); // grade E below the default minimum C
        assertThat(worst(Gates.evaluate("ATS_ERS_GRADE", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(q("acceptableTrials", "0")), env, 0, null, "E"))))
                .isEqualTo(GateOutcome.BLOCK);
        assertThat(codes(Gates.evaluate("PREDICTED_VALUES", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(), env, 0, null, "E"))))
                .containsExactly("predicted_unavailable");
        assertThat(codes(Gates.evaluate("PREDICTED_VALUES", ctx(p, DeviceCodes.Category.SPIROMETRY, List.of(q("fev1PredictedL", "4.1")), env, 0, null, "E"))))
                .containsExactly("predicted_computed");
    }

    @Test
    void audiometryRetestAndAmbientNoise() {
        Env env = new Env();
        String p = "audio.benson.cca200-db";
        List<DeviceObservation.Component> ok = List.of(q("left.1000", "10"), q("retest1000Left", "15"), q("right.1000", "10"), q("retest1000Right", "5"));
        assertThat(worst(Gates.evaluate("AUDIO_TEST_RETEST:TOLERANCE_DB=5", ctx(p, DeviceCodes.Category.AUDIOMETRY, ok, env, 0, null, "E")))).isEqualTo(GateOutcome.PASS);
        List<DeviceObservation.Component> bad = List.of(q("left.1000", "10"), q("retest1000Left", "25"));
        assertThat(codes(Gates.evaluate("AUDIO_TEST_RETEST:TOLERANCE_DB=5", ctx(p, DeviceCodes.Category.AUDIOMETRY, bad, env, 0, null, "E"))))
                .containsExactly("retest_invalid");
        assertThat(worst(Gates.evaluate("AUDIO_TEST_RETEST", ctx(p, DeviceCodes.Category.AUDIOMETRY, List.of(), env, 0, null, "E")))).isEqualTo(GateOutcome.WARN);
        DeviceObservation.Component noise = new DeviceObservation.Component("ambientNoiseFlag", null, "L", "x", "BOOLEAN", null, "true", null, null, null, null);
        assertThat(codes(Gates.evaluate("AUDIO_AMBIENT_NOISE", ctx(p, DeviceCodes.Category.AUDIOMETRY, List.of(noise), env, 0, null, "E"))))
                .containsExactly("ambient_noise_exceeded");
    }

    @Test
    void bloodPressureCuffAndValidation() {
        Env env = new Env();
        String validated = "vitals.welchallyn.csm-pcd01";      // protocol.validationListed = true
        String unlisted = "vitals.nonin.plx-ble";
        assertThat(worst(Gates.evaluate("BP_CUFF_VALIDATION", ctx(validated, DeviceCodes.Category.VITALS, List.of(t("cuffSize", "ADULT")), env, 0, null, "E"))))
                .isEqualTo(GateOutcome.PASS);
        assertThat(codes(Gates.evaluate("BP_CUFF_VALIDATION", ctx(unlisted, DeviceCodes.Category.VITALS, List.of(), env, 0, null, "E"))))
                .containsExactly("bp_device_not_validated", "cuff_size_unknown");
        assertThat(codes(Gates.evaluate("BP_CUFF_VALIDATION", ctx(validated, DeviceCodes.Category.VITALS,
                List.of(t("cuffSize", "S"), q("armCircumferenceCm", "35")), env, 0, null, "E")))).containsExactly("cuff_size_mismatch");
    }

    @Test
    void fitTestExercises() {
        Env env = new Env();
        String p = "fit.tsi.link-webhook";
        List<DeviceObservation.Component> all = List.of(t("protocol", "OSHA_FAST_FFR"), t("exercises", "[\"BENDING\",\"TALKING\",\"HEAD_SIDE_TO_SIDE\",\"HEAD_UP_DOWN\"]"));
        assertThat(worst(Gates.evaluate("FIT_EXERCISES_COMPLETE", ctx(p, DeviceCodes.Category.FIT_TEST, all, env, 0, null, "E")))).isEqualTo(GateOutcome.PASS);
        List<DeviceObservation.Component> part = List.of(t("protocol", "OSHA_FAST_FFR"), t("exercises", "[\"BENDING\"]"));
        assertThat(codes(Gates.evaluate("FIT_EXERCISES_COMPLETE", ctx(p, DeviceCodes.Category.FIT_TEST, part, env, 0, null, "E"))))
                .containsExactly("fit_test_incomplete");
    }

    @Test
    void plausibilityRangesCrossChecksAndOutliers() {
        Env env = new Env();
        String p = "vitals.welchallyn.csm-pcd01";
        assertThat(codes(Gates.evaluate("PLAUSIBILITY", ctx(p, DeviceCodes.Category.VITALS, List.of(q("systolic", "400"), q("diastolic", "80")), env, 0, null, "E"))))
                .contains("implausible_systolic");
        assertThat(codes(Gates.evaluate("PLAUSIBILITY", ctx(p, DeviceCodes.Category.VITALS, List.of(q("systolic", "80"), q("diastolic", "90")), env, 0, null, "E"))))
                .contains("implausible_bp_pair");
        assertThat(codes(Gates.evaluate("PLAUSIBILITY", ctx("spiro.ndd.easyone-connect-gdt", DeviceCodes.Category.SPIROMETRY,
                List.of(q("fev1L", "5.0"), q("fvcL", "4.0")), env, 0, null, "E")))).contains("implausible_fev1_fvc");
        assertThat(codes(Gates.evaluate("PLAUSIBILITY", ctx("da.lifeloc.fc20-serial", DeviceCodes.Category.BREATH_ALCOHOL, List.of(q("brac", "0.7")), env, 0, null,
                "E")))).contains("implausible_brac");
        env.history = List.of(new BigDecimal("120"), new BigDecimal("122"), new BigDecimal("118"));
        assertThat(codes(Gates.evaluate("PLAUSIBILITY", ctx(p, DeviceCodes.Category.VITALS, List.of(q("systolic", "250"), q("diastolic", "100")), env, 0, null, "E"))))
                .contains("outlier_systolic");
        assertThat(Gates.outlier(121, env.history)).isFalse();
        assertThat(Gates.outlier(121, List.of(new BigDecimal("1")))).isFalse();
    }

    @Test
    void clockSkewConsentAttestationOcrAndVerifyBeforePost() {
        Env env = new Env();
        String p = "vitals.welchallyn.csm-pcd01";
        assertThat(codes(Gates.evaluate("CLOCK_SKEW", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 60_000, null, "E")))).containsExactly("clock_in_sync");
        assertThat(codes(Gates.evaluate("CLOCK_SKEW", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 300_000, null, "E")))).containsExactly("clock_skew_corrected");
        assertThat(codes(Gates.evaluate("CLOCK_SKEW", ctx(p, DeviceCodes.Category.VITALS, List.of(), env, -1_300_000, null, "E")))).containsExactly("clock_skew_excessive");
        assertThat(worst(Gates.evaluate("CONSENT", ctx("wear.aggregator.fhir-pcha", DeviceCodes.Category.WEARABLE, List.of(), env, 0, null, null))))
                .isEqualTo(GateOutcome.PASS);
        env.consent = false;
        assertThat(codes(Gates.evaluate("CONSENT", ctx("wear.aggregator.fhir-pcha", DeviceCodes.Category.WEARABLE, List.of(), env, 0, null, null))))
                .containsExactly("consent_missing");
        assertThat(codes(Gates.evaluate("ATTESTATION", ctx("vision.manual.snellen", DeviceCodes.Category.VISION, List.of(), env, 0,
                DeviceCodes.CaptureMethod.MANUAL_ATTESTED, null)))).containsExactly("attestation_incomplete");
        GateContext attested = new GateContext(pkg("vision.manual.snellen"), DeviceCodes.Category.VISION, List.of(), null, null, null, Map.of(), "E1001", AT,
                0, 120, DeviceCodes.CaptureMethod.MANUAL_ATTESTED, null, "Chart", "C-1", "CAL-9", LocalDate.of(2026, 7, 1), null, null, true, env);
        assertThat(codes(Gates.evaluate("ATTESTATION", attested))).containsExactly("attestation_complete");
        GateContext ocr = new GateContext(pkg("poc.magellan.leadcare2-ocr"), DeviceCodes.Category.POC, List.of(), null, null, null, Map.of(), "E1001", AT,
                0, 120, DeviceCodes.CaptureMethod.OCR_VERIFIED, null, null, null, null, null, new BigDecimal("0.80"), new BigDecimal("0.90"), true, env);
        assertThat(codes(Gates.evaluate("OCR_CONFIDENCE", ocr))).containsExactly("ocr_low_confidence");
        List<QualityFlag> all = Gates.run(List.of("PLAUSIBILITY", "VERIFY_BEFORE_POST"), ctx(p, DeviceCodes.Category.VITALS, List.of(), env, 0, null, "E"));
        assertThat(codes(all)).containsExactly("plausible", "clock_in_sync", "verification_required");
        assertThat(GateCatalogue.isKnown("QC_DAILY:SPIRO_SYRINGE_3L")).isTrue();
        assertThat(GateCatalogue.isKnown("MAGIC")).isFalse();
        assertThat(GateCatalogue.param("ATS_ERS_GRADE:MIN=B", "MIN", "C")).isEqualTo("B");
        assertThat(new ArrayList<>(GateCatalogue.GATES.keySet())).hasSize(15);
    }
}
