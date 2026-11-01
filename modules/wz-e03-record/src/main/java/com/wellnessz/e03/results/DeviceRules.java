package com.wellnessz.e03.results;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e03.support.CodeRef;

/**
 * Abnormal-result rules for clinic devices (contract §4.4, E03-BL-011):
 * <ul>
 *   <li>AUDIOMETRY – standard threshold shift: average change ≥ 10 dB at 2, 3 and 4 kHz in either ear versus the baseline
 *       audiogram (the first audiogram becomes the baseline);</li>
 *   <li>SPIROMETRY – FEV1/FVC &lt; 0.70 or FEV1 &lt; 80 % predicted;</li>
 *   <li>VISION – binocular acuity worse than 6/12 or colour plates &lt; 12/15;</li>
 *   <li>ECG – device / reader interpretation ABNORMAL or BORDERLINE (physician review).</li>
 *   <li>Increment 6 (device engine): VITALS – plausible ranges, abnormal at ≥ 140 / ≥ 90 mm[Hg], SpO2 &lt; 94 % or ≥ 38 °C;
 *       POC – abnormal when the engine's reference-range interpretation is H / L / A; FIT_TEST – {@code pass=false};
 *       other categories NORMAL with their coded panel. Audiometry STS uses the OSHA Appendix F age correction when the
 *       engine supplies {@code ageCorrection} for both the current and the baseline audiogram.</li>
 * </ul>
 */
public final class DeviceRules {

    static final Set<String> FREQUENCIES = Set.of("250", "500", "1000", "2000", "3000", "4000", "6000", "8000");
    static final List<String> STS_FREQUENCIES = List.of("2000", "3000", "4000");
    static final double STS_DB = 10.0;

    public static final CodeRef AUDIOGRAM = new CodeRef("LOINC", "28539-6", "Audiogram study");
    public static final CodeRef FEV1_FVC = new CodeRef("LOINC", "19926-5", "FEV1/FVC");
    public static final CodeRef VISION = new CodeRef("LOCAL", "VISION_SCREEN", "Vision screening (acuity and colour plates)");
    public static final CodeRef ECG = new CodeRef("LOINC", "11524-6", "EKG study");

    private DeviceRules() {
    }

    /** Outcome of a device evaluation. */
    public record Evaluation(CodeRef code, String value, String unit, String referenceRange, String flag, Map<String, Object> detail,
                            boolean baseline) {
    }

    // ------------------------------------------------------------------ audiometry

    /**
     * @param measurements {@code {left:{"500":10,...}, right:{...}}} in dB HL
     * @param baseline     the baseline audiogram's measurements, or {@code null} when this is the first audiogram
     * @param baselineId   id of the baseline result (reported in the detail)
     */
    public static Evaluation audiometry(Map<String, Object> measurements, Map<String, Object> baseline, String baselineId) {
        Map<String, Double> left = ear(measurements, "left");
        Map<String, Double> right = ear(measurements, "right");
        double avgL = avg234(left);
        double avgR = avg234(right);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("average234", Map.of("left", round1(avgL), "right", round1(avgR)));
        String value = "Average 2/3/4 kHz: left " + round1(avgL) + " dB, right " + round1(avgR) + " dB";
        if (baseline == null) {
            detail.put("baseline", true);
            return new Evaluation(AUDIOGRAM, value + " (baseline)", "dB HL", null, "NORMAL", detail, true);
        }
        double correction = ageCorrection(measurements, baseline);
        double shiftL = avgL - avg234(ear(baseline, "left")) - correction;
        double shiftR = avgR - avg234(ear(baseline, "right")) - correction;
        if (correction != 0) {
            detail.put("ageCorrectionDb", round1(correction));
        }
        boolean stsL = shiftL >= STS_DB;
        boolean stsR = shiftR >= STS_DB;
        Map<String, Object> sts = new LinkedHashMap<>();
        sts.put("left", stsL);
        sts.put("right", stsR);
        detail.put("sts", sts);
        detail.put("shiftDb", Map.of("left", round1(shiftL), "right", round1(shiftR)));
        detail.put("baselineId", baselineId);
        String flag = stsL || stsR ? "ABNORMAL" : "NORMAL";
        return new Evaluation(AUDIOGRAM, value + (stsL || stsR ? " – standard threshold shift" : ""), "dB HL",
                "STS < 10 dB vs baseline", flag, detail, false);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Double> ear(Map<String, Object> m, String side) {
        Object o = m == null ? null : m.get(side);
        if (!(o instanceof Map<?, ?> raw)) {
            throw ApiException.badRequest("invalid_measurements", "Audiometry needs thresholds for the " + side + " ear");
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<Object, Object>) raw).entrySet()) {
            String f = String.valueOf(e.getKey());
            if (!FREQUENCIES.contains(f) || !(e.getValue() instanceof Number n)) {
                throw ApiException.badRequest("invalid_measurements", "Audiometry thresholds must be numbers at 250–8000 Hz");
            }
            double db = n.doubleValue();
            if (db < -10 || db > 120) {
                throw ApiException.badRequest("invalid_measurements", "Thresholds must be between -10 and 120 dB HL");
            }
            out.put(f, db);
        }
        for (String f : STS_FREQUENCIES) {
            if (!out.containsKey(f)) {
                throw ApiException.badRequest("invalid_measurements", "Thresholds at 2, 3 and 4 kHz are required for both ears");
            }
        }
        return out;
    }

    /**
     * OSHA 29 CFR 1910.95 App. F: average over 2, 3 and 4 kHz of (correction at the current age − correction at the baseline
     * age); 0 when either audiogram carries no {@code ageCorrection} map (supplied by the device engine from its tables).
     */
    @SuppressWarnings("unchecked")
    static double ageCorrection(Map<String, Object> current, Map<String, Object> baseline) {
        if (!(current.get("ageCorrection") instanceof Map<?, ?> now) || baseline == null
                || !(baseline.get("ageCorrection") instanceof Map<?, ?> base)) {
            return 0;
        }
        double sum = 0;
        for (String f : STS_FREQUENCIES) {
            Object a = ((Map<Object, Object>) now).get(f);
            Object b = ((Map<Object, Object>) base).get(f);
            if (!(a instanceof Number na) || !(b instanceof Number nb)) {
                return 0;
            }
            sum += na.doubleValue() - nb.doubleValue();
        }
        return sum / STS_FREQUENCIES.size();
    }

    static double avg234(Map<String, Double> ear) {
        return STS_FREQUENCIES.stream().mapToDouble(ear::get).average().orElse(0);
    }

    // ------------------------------------------------------------------ spirometry

    public static Evaluation spirometry(Map<String, Object> m) {
        double fev1 = positive(m, "fev1L");
        double fvc = positive(m, "fvcL");
        Double fev1Pred = optionalPositive(m, "fev1PredictedL");
        Double fvcPred = optionalPositive(m, "fvcPredictedL");
        double ratio = fev1 / fvc;
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fev1L", fev1);
        detail.put("fvcL", fvc);
        detail.put("ratio", round2(ratio));
        boolean abnormal = ratio < 0.70;
        if (fev1Pred != null) {
            double pct = fev1 / fev1Pred * 100.0;
            detail.put("fev1PercentPredicted", round1(pct));
            abnormal = abnormal || pct < 80.0;
        }
        if (fvcPred != null) {
            detail.put("fvcPercentPredicted", round1(fvc / fvcPred * 100.0));
        }
        return new Evaluation(FEV1_FVC, String.valueOf(round2(ratio)), "ratio", ">= 0.70; FEV1 >= 80 % predicted",
                abnormal ? "ABNORMAL" : "NORMAL", detail, false);
    }

    // ------------------------------------------------------------------ vision

    public static Evaluation vision(Map<String, Object> m) {
        String binocular = text(m, "binocular", true);
        double binocularDecimal = acuity(binocular);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("binocular", binocular);
        String left = text(m, "acuityLeft", false);
        String right = text(m, "acuityRight", false);
        if (left != null) {
            acuity(left);
            detail.put("acuityLeft", left);
        }
        if (right != null) {
            acuity(right);
            detail.put("acuityRight", right);
        }
        boolean abnormal = binocularDecimal < 0.5; // worse than 6/12
        String value = "Binocular " + binocular;
        Object correct = m.get("colourPlatesCorrect");
        Object total = m.get("colourPlatesTotal");
        if (correct != null || total != null) {
            if (!(correct instanceof Number c) || !(total instanceof Number t) || t.intValue() <= 0 || c.intValue() < 0
                    || c.intValue() > t.intValue()) {
                throw ApiException.badRequest("invalid_measurements", "Colour plates need correct ≤ total");
            }
            detail.put("colourPlates", c.intValue() + "/" + t.intValue());
            abnormal = abnormal || c.intValue() * 15 < 12 * t.intValue(); // fewer than 12/15
            value += "; colour plates " + c.intValue() + "/" + t.intValue();
        }
        if (m.get("corrected") instanceof Boolean b) {
            detail.put("corrected", b);
        }
        return new Evaluation(VISION, value, null, "Binocular 6/12 or better; colour plates >= 12/15", abnormal ? "ABNORMAL" : "NORMAL",
                detail, false);
    }

    /** Snellen fraction ("6/9", "20/40") → decimal acuity. */
    static double acuity(String snellen) {
        int slash = snellen.indexOf('/');
        if (slash <= 0 || slash == snellen.length() - 1 || snellen.length() > 10) {
            throw ApiException.badRequest("invalid_measurements", "Acuity must be a Snellen fraction such as 6/9");
        }
        try {
            double num = Double.parseDouble(snellen.substring(0, slash).trim());
            double den = Double.parseDouble(snellen.substring(slash + 1).trim());
            if (num <= 0 || den <= 0) {
                throw new NumberFormatException();
            }
            return num / den;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("invalid_measurements", "Acuity must be a Snellen fraction such as 6/9");
        }
    }

    // ------------------------------------------------------------------ ECG

    public static Evaluation ecg(Map<String, Object> m) {
        String interpretation = text(m, "interpretation", true).toUpperCase(Locale.ROOT);
        if (!Set.of("NORMAL", "ABNORMAL", "BORDERLINE").contains(interpretation)) {
            throw ApiException.badRequest("invalid_measurements", "interpretation must be NORMAL, ABNORMAL or BORDERLINE");
        }
        String rhythm = text(m, "rhythm", false);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("interpretation", interpretation);
        if (rhythm != null) {
            detail.put("rhythm", rhythm);
        }
        String value = interpretation + (rhythm == null ? "" : ", " + rhythm);
        if (m.get("heartRate") instanceof Number hr) {
            detail.put("heartRate", hr.intValue());
            value += ", rate " + hr.intValue() + "/min";
        }
        if (m.get("qtcMs") instanceof Number qtc) {
            detail.put("qtcMs", qtc.intValue());
            value += ", QTc " + qtc.intValue() + " ms";
        }
        return new Evaluation(ECG, value, null, null, "NORMAL".equals(interpretation) ? "NORMAL" : "ABNORMAL", detail, false);
    }

    // ------------------------------------------------------------------ increment 6 categories (device engine)

    public static final CodeRef VITALS = new CodeRef("LOINC", "85353-1", "Vital signs panel");

    public static Evaluation vitals(Map<String, Object> m) {
        Map<String, Object> detail = new LinkedHashMap<>();
        Double sys = range(m, "systolic", 60, 260);
        Double dia = range(m, "diastolic", 30, 160);
        Double pulse = range(m, "pulse", 25, 240);
        Double spo2 = range(m, "spo2", 50, 100);
        Double temp = range(m, "temperatureC", 30, 43);
        StringBuilder value = new StringBuilder();
        if (sys != null && dia != null) {
            value.append("BP ").append(sys.intValue()).append('/').append(dia.intValue()).append(" mm[Hg]");
        }
        if (pulse != null) {
            value.append(value.isEmpty() ? "" : ", ").append("pulse ").append(pulse.intValue()).append("/min");
        }
        if (spo2 != null) {
            value.append(value.isEmpty() ? "" : ", ").append("SpO2 ").append(spo2.intValue()).append(" %");
        }
        if (temp != null) {
            value.append(value.isEmpty() ? "" : ", ").append(round1(temp)).append(" Cel");
        }
        boolean abnormal = sys != null && sys >= 140 || dia != null && dia >= 90 || spo2 != null && spo2 < 94 || temp != null && temp >= 38;
        detail.put("hypertensionRange", sys != null && sys >= 140 || dia != null && dia >= 90);
        return new Evaluation(VITALS, value.isEmpty() ? "Vital signs recorded" : value.toString(), null, "BP < 140/90; SpO2 >= 94 %; < 38 Cel",
                abnormal ? "ABNORMAL" : "NORMAL", detail, false);
    }

    /** Point of care: abnormal when an analyte is outside its reference range ({@code interpretations} from the engine). */
    public static Evaluation poc(Map<String, Object> m) {
        Map<String, Object> detail = new LinkedHashMap<>();
        boolean abnormal = false;
        if (m.get("interpretations") instanceof Map<?, ?> interp) {
            for (Map.Entry<?, ?> e : interp.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (!"N".equals(v)) {
                    abnormal = true;
                    detail.put(String.valueOf(e.getKey()), v);
                }
            }
        }
        return new Evaluation(new CodeRef("LOCAL", "POC_PANEL", "Point-of-care panel"), summary(m), null, "Within reference ranges",
                abnormal ? "ABNORMAL" : "NORMAL", detail, false);
    }

    public static Evaluation fitTest(Map<String, Object> m) {
        Object pass = m.get("pass");
        boolean failed = Boolean.FALSE.equals(pass) || "false".equals(String.valueOf(pass));
        Object ff = m.get("overallFitFactor");
        String value = "Overall fit factor " + (ff instanceof Number n ? n.intValue() : "n/a") + (failed ? " (fail)" : " (pass)");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("pass", !failed);
        return new Evaluation(new CodeRef("LOCAL", "FIT_TEST", "Respirator fit test"), value, null, "Pass", failed ? "ABNORMAL" : "NORMAL", detail, false);
    }

    /** BODY_COMPOSITION, ABPM_HOLTER, ERGONOMICS, IMAGING: recorded, coded panel, NORMAL (clinical review in the module). */
    public static Evaluation other(String category, Map<String, Object> m) {
        CodeRef code = switch (category) {
            case "BODY_COMPOSITION" -> new CodeRef("LOINC", "29463-7", "Body weight and composition");
            case "IMAGING" -> new CodeRef("LOCAL", "IMAGING_STUDY", "Imaging study");
            case "ABPM_HOLTER" -> new CodeRef("LOCAL", "ABPM_HOLTER", "Ambulatory blood pressure / Holter");
            default -> new CodeRef("LOCAL", category.length() > 40 ? category.substring(0, 40) : category, category);
        };
        return new Evaluation(code, summary(m), null, null, "NORMAL", new LinkedHashMap<>(), false);
    }

    static Double range(Map<String, Object> m, String key, double min, double max) {
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        if (!(o instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
            throw ApiException.badRequest("invalid_measurements", key + " is outside the plausible range");
        }
        return n.doubleValue();
    }

    static String summary(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof Number n) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append(' ').append(new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString());
            }
        }
        String s = sb.isEmpty() ? "Recorded" : sb.toString();
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    // ------------------------------------------------------------------ helpers

    private static double positive(Map<String, Object> m, String key) {
        Double v = optionalPositive(m, key);
        if (v == null) {
            throw ApiException.badRequest("invalid_measurements", key + " is required");
        }
        return v;
    }

    private static Double optionalPositive(Map<String, Object> m, String key) {
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        if (!(o instanceof Number n) || n.doubleValue() <= 0 || n.doubleValue() > 15) {
            throw ApiException.badRequest("invalid_measurements", key + " must be a positive volume in litres");
        }
        return n.doubleValue();
    }

    private static String text(Map<String, Object> m, String key, boolean required) {
        Object o = m.get(key);
        if (o == null) {
            if (required) {
                throw ApiException.badRequest("invalid_measurements", key + " is required");
            }
            return null;
        }
        if (!(o instanceof String s) || s.isBlank() || s.length() > 40) {
            throw ApiException.badRequest("invalid_measurements", key + " must be short text");
        }
        return s.trim();
    }

    static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
