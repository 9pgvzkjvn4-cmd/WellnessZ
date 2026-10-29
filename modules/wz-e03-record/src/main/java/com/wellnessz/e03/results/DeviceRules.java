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
        double shiftL = avgL - avg234(ear(baseline, "left"));
        double shiftR = avgR - avg234(ear(baseline, "right"));
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
