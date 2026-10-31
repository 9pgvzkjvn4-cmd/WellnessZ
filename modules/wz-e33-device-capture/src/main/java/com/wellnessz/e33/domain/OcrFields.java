package com.wellnessz.e33.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the text the platform OCR engine read from a device display / printout into suggested form fields (P9, contract 14.2
 * {@code POST /device-capture/ocr}). The OCR SPI returns text only, so the confidence is derived: a known label for the
 * category followed by one number inside the plausible range scores 0.95, a known label with an implausible or ambiguous number
 * 0.50. Fields below {@code device.ocr.min-confidence} are returned without a value – the clinician types them – and
 * verification of every value is always mandatory.
 */
public final class OcrFields {

    public record Field(String key, BigDecimal value, BigDecimal confidence) {
    }

    private record Label(String key, Pattern pattern, double min, double max) {
    }

    private static final String NUMBER = "\\s*[:=]?\\s*(-?\\d{1,4}(?:[.,]\\d{1,3})?)";

    private static final Map<String, List<Label>> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("VITALS", List.of(label("systolic", "SYS(?:TOLIC)?", 60, 260), label("diastolic", "DIA(?:STOLIC)?", 30, 160),
                label("pulse", "(?:PUL(?:SE)?|HR|PR)", 25, 240), label("spo2", "SPO2", 50, 100), label("temperatureC", "TEMP", 30, 43)));
        LABELS.put("BODY_COMPOSITION", List.of(label("weightKg", "(?:WEIGHT|WT)", 20, 300), label("heightCm", "(?:HEIGHT|HT)", 100, 230),
                label("bmi", "BMI", 10, 80), label("fatPct", "(?:FAT|BF)%?", 2, 70)));
        LABELS.put("SPIROMETRY", List.of(label("fev1FvcRatio", "FEV1/FVC", 0.2, 1.0), label("fev1L", "FEV1(?!/)", 0.3, 8),
                label("fvcL", "FVC", 0.3, 9), label("pefLs", "PEF", 0.5, 18)));
        LABELS.put("POC", List.of(label("hba1c", "HBA1C", 3, 20), label("glucoseMmol", "GLU(?:COSE)?", 1, 40),
                label("hb", "HB(?!A1C)", 3, 25), label("cholesterolMmol", "(?:CHOL|TC)", 1, 20)));
        LABELS.put("FIT_TEST", List.of(label("overallFitFactor", "(?:FIT\\s*FACTOR|FF)", 1, 100000)));
        LABELS.put("BREATH_ALCOHOL", List.of(label("brac", "(?:BRAC|RESULT)", 0, 0.6)));
    }

    private OcrFields() {
    }

    private static Label label(String key, String regex, double min, double max) {
        return new Label(key, Pattern.compile("\\b" + regex + NUMBER, Pattern.CASE_INSENSITIVE), min, max);
    }

    /**
     * @param minConfidence fields below it are returned with {@code value = null}
     */
    public static List<Field> parse(String category, String text, BigDecimal minConfidence) {
        List<Field> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (Label l : LABELS.getOrDefault(category, List.of())) {
            Matcher m = l.pattern().matcher(upper);
            if (!m.find()) {
                continue;
            }
            BigDecimal v = new BigDecimal(m.group(1).replace(',', '.'));
            boolean ambiguous = m.find();
            boolean plausible = v.doubleValue() >= l.min() && v.doubleValue() <= l.max();
            BigDecimal confidence = plausible && !ambiguous ? new BigDecimal("0.95") : new BigDecimal("0.50");
            out.add(new Field(l.key(), confidence.compareTo(minConfidence) >= 0 ? v : null, confidence));
        }
        return out;
    }
}
