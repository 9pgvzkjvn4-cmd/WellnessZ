package com.wellnessz.e12.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Screening thresholds (E12-F02). Each measure has one configurable comparison ({@code promotion.screening.threshold.<measure>},
 * e.g. {@code >=140}); a measure matching its comparison is abnormal and opens a follow-up. Pure – no Spring, unit tested.
 */
public final class ScreeningThresholds {

    /** Measures in contract order with their flag codes. */
    public static final Map<String, String> FLAGS;
    /** Defaults used when a configuration entry is missing or malformed. */
    public static final Map<String, String> DEFAULTS;

    static {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("systolic", "SYSTOLIC");
        f.put("diastolic", "DIASTOLIC");
        f.put("bmi", "BMI");
        f.put("glucoseMmol", "GLUCOSE");
        f.put("cholesterolMmol", "CHOLESTEROL");
        f.put("waistCm", "WAIST");
        f.put("mskScore", "MSK_SCORE");
        f.put("wellbeingScore", "WELLBEING_SCORE");
        FLAGS = java.util.Collections.unmodifiableMap(f);
        Map<String, String> d = new LinkedHashMap<>();
        d.put("systolic", ">=140");
        d.put("diastolic", ">=90");
        d.put("bmi", ">=30");
        d.put("glucoseMmol", ">=7.0");
        d.put("cholesterolMmol", ">=6.5");
        d.put("waistCm", ">=102");
        d.put("mskScore", ">=7");
        d.put("wellbeingScore", "<=40");
        DEFAULTS = java.util.Collections.unmodifiableMap(d);
    }

    private static final Pattern EXPR = Pattern.compile("\\s*(>=|<=|>|<)\\s*(-?[0-9]{1,5}(\\.[0-9]{1,3})?)\\s*");

    private ScreeningThresholds() {
    }

    /** One comparison, e.g. {@code systolic >= 140}. */
    public record Threshold(String measure, String operator, BigDecimal limit) {

        public boolean matches(BigDecimal value) {
            if (value == null) {
                return false;
            }
            int c = value.compareTo(limit);
            return switch (operator) {
                case ">=" -> c >= 0;
                case ">" -> c > 0;
                case "<=" -> c <= 0;
                case "<" -> c < 0;
                default -> false;
            };
        }

        /** Flag code, e.g. {@code SYSTOLIC_HIGH}, {@code WELLBEING_SCORE_LOW}. */
        public String flag() {
            return FLAGS.getOrDefault(measure, measure.toUpperCase(java.util.Locale.ROOT)) + (operator.startsWith(">") ? "_HIGH" : "_LOW");
        }

        public String expression() {
            return operator + limit.toPlainString();
        }
    }

    public static Optional<Threshold> parse(String measure, String expression) {
        if (measure == null || expression == null) {
            return Optional.empty();
        }
        Matcher m = EXPR.matcher(expression);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Threshold(measure, m.group(1), new BigDecimal(m.group(2))));
    }

    /** Thresholds for all measures: the configured expression where it parses, otherwise the default. */
    public static Map<String, Threshold> resolve(Function<String, Optional<String>> configured) {
        Map<String, Threshold> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> d : DEFAULTS.entrySet()) {
            Threshold t = configured.apply(d.getKey()).flatMap(e -> parse(d.getKey(), e))
                    .orElseGet(() -> parse(d.getKey(), d.getValue()).orElseThrow());
            out.put(d.getKey(), t);
        }
        return out;
    }

    /** Flag codes of all measures that match their threshold (empty = NORMAL). */
    public static List<String> evaluate(Map<String, BigDecimal> measures, Map<String, Threshold> thresholds) {
        List<String> flags = new ArrayList<>();
        for (Map.Entry<String, Threshold> t : thresholds.entrySet()) {
            BigDecimal v = measures.get(t.getKey());
            if (v != null && t.getValue().matches(v)) {
                flags.add(t.getValue().flag());
            }
        }
        return flags;
    }

    public static String outcome(List<String> flags) {
        return flags.isEmpty() ? "NORMAL" : "ABNORMAL";
    }
}
