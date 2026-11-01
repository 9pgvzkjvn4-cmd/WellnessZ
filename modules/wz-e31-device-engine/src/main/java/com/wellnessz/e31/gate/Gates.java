package com.wellnessz.e31.gate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.GateOutcome;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservation.QualityFlag;
import com.wellnessz.casekernel.support.DeviceCalibrationCheck;
import com.wellnessz.e31.driver.DriverPackage;

/**
 * VALIDATE stage: the quality gates of contract section 10, executed in the order of the binding (fallback: the package).
 * {@code CLOCK_SKEW} runs for every capture; {@code VERIFY_BEFORE_POST} always closes the list. Messages are plain language
 * and PHI-free (no values of the person).
 */
public final class Gates {

    static final List<String> GRADES = List.of("A", "B", "C", "D", "E", "U", "F");
    static final Map<String, Set<String>> FIT_PROTOCOLS = Map.of(
            "OSHA_QNFT", Set.of("NORMAL_BREATHING", "DEEP_BREATHING", "HEAD_SIDE_TO_SIDE", "HEAD_UP_DOWN", "TALKING", "GRIMACE", "BENDING_OVER",
                    "NORMAL_BREATHING_2"),
            "OSHA_FAST_FFR", Set.of("BENDING", "TALKING", "HEAD_SIDE_TO_SIDE", "HEAD_UP_DOWN"),
            "OSHA_QLFT", Set.of("NORMAL_BREATHING", "DEEP_BREATHING", "HEAD_SIDE_TO_SIDE", "HEAD_UP_DOWN", "TALKING", "BENDING_OVER",
                    "NORMAL_BREATHING_2"));
    /** Adult cuff sizes and their arm-circumference ranges (cm), per common manufacturer labelling. */
    static final Map<String, double[]> CUFFS = Map.of("S", new double[] {17, 22}, "SMALL", new double[] {17, 22}, "M", new double[] {22, 32},
            "ADULT", new double[] {22, 32}, "L", new double[] {32, 42}, "LARGE", new double[] {32, 42}, "XL", new double[] {42, 52});

    private Gates() {
    }

    public static List<QualityFlag> run(List<String> gates, GateContext ctx) {
        List<QualityFlag> out = new ArrayList<>();
        Set<String> ran = new HashSet<>();
        for (String g : gates) {
            String code = GateCatalogue.code(g);
            if (!ran.add(code)) {
                continue;
            }
            if ("VERIFY_BEFORE_POST".equals(code)) {
                continue;
            }
            out.addAll(evaluate(g, ctx));
        }
        if (!ran.contains("CLOCK_SKEW")) {
            out.addAll(clockSkew(ctx));
        }
        out.add(ctx.verifyBeforePost()
                ? new QualityFlag("VERIFY_BEFORE_POST", GateOutcome.INFO, "verification_required", "A clinician must verify the result before it is filed")
                : new QualityFlag("VERIFY_BEFORE_POST", GateOutcome.INFO, "auto_post", "Filed automatically (verification not required for this form)"));
        return out;
    }

    public static List<QualityFlag> evaluate(String gate, GateContext ctx) {
        return switch (GateCatalogue.code(gate)) {
            case "CALIBRATION" -> calibration(ctx);
            case "QC_DAILY" -> qcDaily(GateCatalogue.parameter(gate), ctx);
            case "QC_LOT" -> qcLot(ctx);
            case "ATS_ERS_GRADE" -> atsErs(GateCatalogue.param(gate, "MIN", "C"), ctx);
            case "PREDICTED_VALUES" -> predicted(ctx);
            case "AUDIO_TEST_RETEST" -> retest(Double.parseDouble(GateCatalogue.param(gate, "TOLERANCE_DB", "5")), ctx);
            case "AUDIO_AMBIENT_NOISE" -> ambient(ctx);
            case "BP_CUFF_VALIDATION" -> bpCuff(ctx);
            case "FIT_EXERCISES_COMPLETE" -> fitExercises(ctx);
            case "PLAUSIBILITY" -> plausibility(ctx);
            case "CLOCK_SKEW" -> clockSkew(ctx);
            case "CONSENT" -> consent(ctx);
            case "ATTESTATION" -> attestation(ctx);
            case "OCR_CONFIDENCE" -> ocr(ctx);
            default -> List.of(new QualityFlag(GateCatalogue.code(gate), GateOutcome.INFO, "gate_not_implemented",
                    "Gate not known to this engine version"));
        };
    }

    static QualityFlag flag(String gate, GateOutcome o, String code, String message) {
        return new QualityFlag(gate, o, code, message);
    }

    static List<QualityFlag> one(String gate, GateOutcome o, String code, String message) {
        return List.of(flag(gate, o, code, message));
    }

    // ------------------------------------------------------------------ calibration and QC

    static List<QualityFlag> calibration(GateContext ctx) {
        if (ctx.captureMethod() == DeviceCodes.CaptureMethod.MANUAL_ATTESTED && ctx.equipmentDeviceId() == null) {
            return one("CALIBRATION", GateOutcome.INFO, "calibration_attested", "Calibration attested by the clinician (manual entry)");
        }
        List<DeviceCalibrationCheck.Status> verdicts = ctx.env().calibration(ctx.equipmentDeviceId(), ctx.day());
        DeviceCalibrationCheck.Verdict worst = DeviceCalibrationCheck.Verdict.UNKNOWN;
        DeviceCalibrationCheck.Status worstStatus = null;
        for (DeviceCalibrationCheck.Status s : verdicts) {
            if (rank(s.verdict()) > rank(worst) || worstStatus == null) {
                worst = s.verdict();
                worstStatus = s;
            }
        }
        if (ctx.equipmentDeviceId() == null || verdicts.isEmpty()) {
            worst = DeviceCalibrationCheck.Verdict.UNKNOWN;
        }
        return switch (worst) {
            case OUT_OF_CALIBRATION -> one("CALIBRATION", GateOutcome.BLOCK, "device_out_of_calibration",
                    "Expired calibration: the device is out of calibration" + (worstStatus.calibrationDueOn() == null ? ""
                            : " (due " + worstStatus.calibrationDueOn() + ")") + " – the result cannot be posted. Recalibrate or use another device.");
            case OUT_OF_SERVICE -> one("CALIBRATION", GateOutcome.BLOCK, "device_out_of_service",
                    "The device is out of service – the result cannot be posted. Use another device.");
            case VALID -> one("CALIBRATION", GateOutcome.PASS, "calibration_valid", "Calibration in date");
            default -> ctx.env().requireRegisteredDevices()
                    ? one("CALIBRATION", GateOutcome.BLOCK, "device_not_registered", "The device is not in the equipment register – the result cannot be posted.")
                    : one("CALIBRATION", GateOutcome.WARN, "device_not_registered", "The device is not in the equipment register; calibration could not be checked.");
        };
    }

    static int rank(DeviceCalibrationCheck.Verdict v) {
        return switch (v) {
            case OUT_OF_SERVICE -> 3;
            case OUT_OF_CALIBRATION -> 2;
            case VALID -> 1;
            default -> 0;
        };
    }

    static List<QualityFlag> qcDaily(String checkType, GateContext ctx) {
        DeviceCatalogueLookup.QcStatus qc = ctx.env().qc(ctx.deviceInstanceId(), ctx.day(), null);
        String label = checkType == null ? "QC check" : checkType.replace('_', ' ').toLowerCase(Locale.ROOT);
        return switch (qc.verdict()) {
            case "DUE" -> one("QC_DAILY", GateOutcome.BLOCK, "qc_due", "Device locked out: the " + label + " is due – record a passing check first.");
            case "FAILED" -> one("QC_DAILY", GateOutcome.BLOCK, "qc_failed", "Device locked out: the last " + label + " failed.");
            case "VALID", "NOT_REQUIRED" -> one("QC_DAILY", GateOutcome.PASS, "qc_valid", "QC check in date");
            default -> one("QC_DAILY", GateOutcome.WARN, "qc_status_unknown", "The QC status of the device is not known.");
        };
    }

    static List<QualityFlag> qcLot(GateContext ctx) {
        String lot = ctx.text("qcLot");
        if (lot == null || lot.isBlank()) {
            return one("QC_LOT", GateOutcome.BLOCK, "qc_lot_invalid", "No reagent / QC lot in the result – it cannot be posted.");
        }
        DeviceCatalogueLookup.QcStatus qc = ctx.env().qc(ctx.deviceInstanceId(), ctx.day(), lot);
        if (qc.lotExpiry() != null && qc.lotExpiry().isBefore(ctx.day()) || qc.blocks()) {
            return one("QC_LOT", GateOutcome.BLOCK, "qc_lot_invalid", "The reagent lot is expired or has no passing QC run.");
        }
        if ("VALID".equals(qc.verdict()) || "NOT_REQUIRED".equals(qc.verdict())) {
            return one("QC_LOT", GateOutcome.PASS, "qc_lot_valid", "Reagent lot in date with passing QC");
        }
        return one("QC_LOT", GateOutcome.WARN, "qc_status_unknown", "The QC status of the reagent lot is not known.");
    }

    // ------------------------------------------------------------------ spirometry

    /** ATS/ERS grade: from the device, or computed from the manoeuvres ({@code fev1Trials} / {@code fvcTrials}, litres). */
    public static String grade(GateContext ctx) {
        String device = ctx.text("sessionGrade");
        if (device != null && GRADES.contains(device.strip().toUpperCase(Locale.ROOT))) {
            return device.strip().toUpperCase(Locale.ROOT);
        }
        List<Double> fev1 = trials(ctx.text("fev1Trials"));
        List<Double> fvc = trials(ctx.text("fvcTrials"));
        if (fev1.isEmpty()) {
            BigDecimal n = ctx.number("acceptableTrials");
            return n != null && n.intValue() == 0 ? "F" : null;
        }
        return gradeFromTrials(fev1, fvc);
    }

    /** ≥ 3 acceptable: repeatability (largest − second largest of FEV1 and FVC) ≤ 0.150 L → A, ≤ 0.200 → B, ≤ 0.250 → C, else D. */
    public static String gradeFromTrials(List<Double> fev1, List<Double> fvc) {
        int n = fev1.size();
        if (n == 0) {
            return "F";
        }
        if (n == 1) {
            return "E";
        }
        double spread = Math.max(repeatability(fev1), fvc.size() >= 2 ? repeatability(fvc) : 0);
        if (n >= 3) {
            return spread <= 0.150 ? "A" : spread <= 0.200 ? "B" : spread <= 0.250 ? "C" : "D";
        }
        return spread <= 0.250 ? "D" : "E";
    }

    static double repeatability(List<Double> v) {
        List<Double> s = v.stream().sorted((a, b) -> Double.compare(b, a)).toList();
        return s.size() < 2 ? 0 : s.get(0) - s.get(1);
    }

    static List<Double> trials(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>();
        for (String p : text.replace("[", "").replace("]", "").split("[;,| ]+")) {
            if (!p.isBlank()) {
                try {
                    out.add(Double.parseDouble(p.strip()));
                } catch (NumberFormatException e) {
                    return List.of();
                }
            }
        }
        return out;
    }

    static List<QualityFlag> atsErs(String minimum, GateContext ctx) {
        String grade = grade(ctx);
        if (grade == null) {
            return one("ATS_ERS_GRADE", GateOutcome.WARN, "grade_unavailable", "No ATS/ERS session grade – review the manoeuvres.");
        }
        if ("F".equals(grade)) {
            return one("ATS_ERS_GRADE", GateOutcome.BLOCK, "grade_F", "ATS/ERS grade F: no acceptable manoeuvre – repeat the test.");
        }
        int min = GRADES.indexOf(minimum.toUpperCase(Locale.ROOT));
        if (min >= 0 && GRADES.indexOf(grade) > min) {
            return one("ATS_ERS_GRADE", GateOutcome.WARN, "grade_below_minimum",
                    "ATS/ERS grade " + grade + " is below the minimum " + minimum + " – consider a re-test.");
        }
        return one("ATS_ERS_GRADE", GateOutcome.PASS, "grade_" + grade, "ATS/ERS grade " + grade);
    }

    static List<QualityFlag> predicted(GateContext ctx) {
        return ctx.component("fev1PredictedL").isPresent()
                ? one("PREDICTED_VALUES", GateOutcome.INFO, "predicted_computed", "GLI-2012 predicted values and LLN added")
                : one("PREDICTED_VALUES", GateOutcome.WARN, "predicted_unavailable",
                        "Predicted values unavailable: age, sex or height missing.");
    }

    // ------------------------------------------------------------------ audiometry

    static List<QualityFlag> retest(double tolerance, GateContext ctx) {
        List<QualityFlag> out = new ArrayList<>();
        boolean any = false;
        for (String[] ear : new String[][] {{"left.1000", "retest1000Left", "left"}, {"right.1000", "retest1000Right", "right"}}) {
            BigDecimal first = ctx.number(ear[0]);
            BigDecimal again = ctx.number(ear[1]);
            if (first == null || again == null) {
                continue;
            }
            any = true;
            if (Math.abs(first.doubleValue() - again.doubleValue()) > tolerance) {
                out.add(flag("AUDIO_TEST_RETEST", GateOutcome.WARN, "retest_invalid",
                        "1 kHz re-test of the " + ear[2] + " ear differs by more than " + (int) tolerance + " dB – re-test the ear."));
            }
        }
        if (!any) {
            return one("AUDIO_TEST_RETEST", GateOutcome.WARN, "retest_invalid", "No 1 kHz re-test recorded – re-test before filing.");
        }
        return out.isEmpty() ? one("AUDIO_TEST_RETEST", GateOutcome.PASS, "retest_valid", "1 kHz re-test within tolerance") : out;
    }

    static List<QualityFlag> ambient(GateContext ctx) {
        return "true".equals(ctx.text("ambientNoiseFlag"))
                ? one("AUDIO_AMBIENT_NOISE", GateOutcome.WARN, "ambient_noise_exceeded", "Ambient noise exceeded the permissible level during the test.")
                : one("AUDIO_AMBIENT_NOISE", GateOutcome.PASS, "ambient_noise_ok", "Ambient noise within limits");
    }

    // ------------------------------------------------------------------ blood pressure, fit test

    static List<QualityFlag> bpCuff(GateContext ctx) {
        List<QualityFlag> out = new ArrayList<>();
        Object listed = ctx.instanceParameters().get("bpValidated");
        boolean validated = listed != null ? Boolean.parseBoolean(String.valueOf(listed))
                : ctx.pkg().protocol() != null && ctx.pkg().protocol().path("validationListed").asBoolean(false);
        if (!validated) {
            out.add(flag("BP_CUFF_VALIDATION", GateOutcome.WARN, "bp_device_not_validated",
                    "The blood-pressure device is not listed as validated to ISO 81060-2."));
        }
        String cuff = ctx.text("cuffSize");
        BigDecimal arm = ctx.number("armCircumferenceCm");
        if (cuff == null || cuff.isBlank()) {
            out.add(flag("BP_CUFF_VALIDATION", GateOutcome.WARN, "cuff_size_unknown", "Cuff size not recorded."));
        } else if (arm != null) {
            double[] range = CUFFS.get(cuff.strip().toUpperCase(Locale.ROOT));
            if (range == null || arm.doubleValue() < range[0] || arm.doubleValue() > range[1]) {
                out.add(flag("BP_CUFF_VALIDATION", GateOutcome.WARN, "cuff_size_mismatch", "The cuff size does not match the arm circumference."));
            }
        }
        return out.isEmpty() ? one("BP_CUFF_VALIDATION", GateOutcome.PASS, "cuff_valid", "Validated device and matching cuff") : out;
    }

    static List<QualityFlag> fitExercises(GateContext ctx) {
        String protocol = ctx.text("protocol");
        Set<String> required = protocol == null ? null : FIT_PROTOCOLS.get(protocol.strip().toUpperCase(Locale.ROOT));
        Set<String> done = Arrays.stream((ctx.text("exercises") == null ? "" : ctx.text("exercises"))
                        .replaceAll("[\\[\\]\"{}]", " ").split("[,; ]+"))
                .map(s -> s.strip().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty() && !s.contains(":")).collect(Collectors.toSet());
        if (required == null) {
            return done.size() >= 4 ? one("FIT_EXERCISES_COMPLETE", GateOutcome.PASS, "fit_exercises_complete", "All exercises present")
                    : one("FIT_EXERCISES_COMPLETE", GateOutcome.BLOCK, "fit_test_incomplete", "The fit test is incomplete – exercises are missing.");
        }
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(done);
        return missing.isEmpty() ? one("FIT_EXERCISES_COMPLETE", GateOutcome.PASS, "fit_exercises_complete", "All protocol exercises present")
                : one("FIT_EXERCISES_COMPLETE", GateOutcome.BLOCK, "fit_test_incomplete",
                        "The fit test is incomplete – " + missing.size() + " protocol exercise(s) missing.");
    }

    // ------------------------------------------------------------------ plausibility

    static List<QualityFlag> plausibility(GateContext ctx) {
        List<QualityFlag> out = new ArrayList<>();
        for (DeviceObservation.Component c : ctx.components()) {
            if (c.value() == null) {
                continue;
            }
            DriverPackage.Mapping m = ctx.pkg().mapping(c.key()).orElse(null);
            if (m != null && (m.plausibleMin() != null && c.value().compareTo(m.plausibleMin()) < 0
                    || m.plausibleMax() != null && c.value().compareTo(m.plausibleMax()) > 0)) {
                out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_" + c.key(),
                        "The value of " + c.display() + " is outside the physiologically plausible range – re-measure."));
            }
            if (c.key().matches("(left|right)\\.\\d{3,4}") && (c.value().doubleValue() < -10 || c.value().doubleValue() > 120)) {
                out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_" + c.key(), "Hearing thresholds must lie between −10 and 120 dB HL."));
            }
        }
        BigDecimal sys = ctx.number("systolic");
        BigDecimal dia = ctx.number("diastolic");
        if (sys != null && dia != null && sys.compareTo(dia) <= 0) {
            out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_bp_pair", "Systolic pressure must exceed diastolic pressure – re-measure."));
        }
        BigDecimal fev1 = ctx.number("fev1L");
        BigDecimal fvc = ctx.number("fvcL");
        if (fev1 != null && fvc != null && fev1.compareTo(fvc) > 0) {
            out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_fev1_fvc", "FEV1 cannot exceed FVC – check the manoeuvre."));
        }
        BigDecimal spo2 = ctx.number("spo2");
        if (spo2 != null && spo2.compareTo(BigDecimal.valueOf(100)) > 0) {
            out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_spo2", "SpO2 cannot exceed 100 %."));
        }
        BigDecimal brac = ctx.number("brac");
        if (brac != null && brac.compareTo(new BigDecimal("0.6")) > 0) {
            out.add(flag("PLAUSIBILITY", GateOutcome.BLOCK, "implausible_brac", "Breath alcohol above 0.6 g/210 L is implausible – re-test."));
        }
        if (out.isEmpty() && ctx.employeeId() != null) {
            for (DeviceObservation.Component c : ctx.components()) {
                if (c.value() == null || c.key().endsWith("PredictedL") || c.key().endsWith("LlnL")) {
                    continue;
                }
                List<BigDecimal> history = ctx.env().history(ctx.employeeId(), ctx.category().name(), c.key(), 3);
                if (outlier(c.value().doubleValue(), history)) {
                    out.add(flag("PLAUSIBILITY", GateOutcome.WARN, "outlier_" + c.key(),
                            c.display() + " differs strongly from the employee's last results – confirm before filing."));
                }
            }
        }
        return out.isEmpty() ? one("PLAUSIBILITY", GateOutcome.PASS, "plausible", "Values plausible") : out;
    }

    /** More than 4 SD from the mean of at least three previous values (SD floor: 5 % of the mean). */
    public static boolean outlier(double value, List<BigDecimal> history) {
        if (history.size() < 3) {
            return false;
        }
        double mean = history.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double var = history.stream().mapToDouble(h -> Math.pow(h.doubleValue() - mean, 2)).sum() / (history.size() - 1);
        double sd = Math.max(Math.sqrt(var), Math.abs(mean) * 0.05);
        return sd > 0 && Math.abs(value - mean) > 4 * sd;
    }

    // ------------------------------------------------------------------ time, consent, fall-backs

    static List<QualityFlag> clockSkew(GateContext ctx) {
        long abs = Math.abs(ctx.clockSkewMillis()) / 1000;
        if (abs > 10 * ctx.maxSkewSeconds()) {
            return one("CLOCK_SKEW", GateOutcome.BLOCK, "clock_skew_excessive",
                    "The device clock is far off (more than " + 10 * ctx.maxSkewSeconds() + " s) – the result is held for review.");
        }
        if (abs > ctx.maxSkewSeconds()) {
            return one("CLOCK_SKEW", GateOutcome.WARN, "clock_skew_corrected", "The device clock was off by " + abs + " s; the time was corrected.");
        }
        return one("CLOCK_SKEW", GateOutcome.PASS, "clock_in_sync", "Clock within tolerance");
    }

    static List<QualityFlag> consent(GateContext ctx) {
        if ("KIOSK".equals(ctx.sessionMode())) {
            return one("CONSENT", GateOutcome.PASS, "consent_kiosk", "Kiosk consent given in the session");
        }
        String purpose = ctx.category() == DeviceCodes.Category.WEARABLE ? "WEARABLE_DATA" : "KIOSK_SELF_MEASUREMENT";
        if (ctx.employeeId() != null && ctx.env().consent(ctx.employeeId(), purpose)) {
            return one("CONSENT", GateOutcome.PASS, "consent_active", "Active consent");
        }
        return one("CONSENT", GateOutcome.BLOCK, "consent_missing", "No active consent for this data – it is not stored.");
    }

    static List<QualityFlag> attestation(GateContext ctx) {
        if (ctx.captureMethod() != DeviceCodes.CaptureMethod.MANUAL_ATTESTED) {
            return one("ATTESTATION", GateOutcome.PASS, "attestation_not_required", "Device capture – no attestation needed");
        }
        boolean complete = notBlank(ctx.attestedModel()) && notBlank(ctx.attestedSerial()) && notBlank(ctx.attestedCalibrationId())
                && ctx.attestedCalibrationDate() != null;
        return complete ? one("ATTESTATION", GateOutcome.PASS, "attestation_complete", "Model, serial and calibration attested")
                : one("ATTESTATION", GateOutcome.BLOCK, "attestation_incomplete", "Attest the device model, serial and calibration before filing.");
    }

    static List<QualityFlag> ocr(GateContext ctx) {
        if (ctx.captureMethod() != DeviceCodes.CaptureMethod.OCR_VERIFIED) {
            return one("OCR_CONFIDENCE", GateOutcome.INFO, "ocr_not_used", "Values not read by OCR");
        }
        boolean low = ctx.ocrConfidence() == null || ctx.ocrMinConfidence() != null && ctx.ocrConfidence().compareTo(ctx.ocrMinConfidence()) < 0;
        return low ? one("OCR_CONFIDENCE", GateOutcome.INFO, "ocr_low_confidence", "Some fields were read with low confidence and typed by the clinician.")
                : one("OCR_CONFIDENCE", GateOutcome.INFO, "ocr_confident", "OCR values confirmed by the clinician");
    }

    static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
