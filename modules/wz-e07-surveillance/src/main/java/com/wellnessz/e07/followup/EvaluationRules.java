package com.wellnessz.e07.followup;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure result-evaluation rules of the surveillance programmes (E07-F07):
 * <ul>
 *   <li>{@code STS} – the E03 audiogram is flagged ABNORMAL and its detail reports a standard threshold shift in either ear;</li>
 *   <li>{@code SPIRO_DECLINE} – FEV1 dropped by at least {@code fev1DropPct} % (default 15) versus the previous spirometry, or
 *       FEV1/FVC below {@code ratioMin} (default 0.70);</li>
 *   <li>{@code BM_LIMIT} – a biomonitoring value at or above {@code limit} (e.g. blood lead 5671-3 ≥ 30 µg/dL);</li>
 *   <li>{@code VISION} – vision screening flagged ABNORMAL;</li>
 *   <li>{@code FLAG_ABNORMAL} – any flag other than NORMAL.</li>
 * </ul>
 */
public final class EvaluationRules {

    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

    private EvaluationRules() {
    }

    /** Panel test names a recorded result can satisfy, e.g. LAB 5671-3 → {@code LAB:5671-3}, {@code BIOMONITORING:5671-3}. */
    public static List<String> testsFor(String category, String code) {
        if (category == null) {
            return List.of();
        }
        return switch (category) {
            case "AUDIOMETRY", "SPIROMETRY", "VISION", "ECG" -> List.of(category);
            default -> code == null ? List.of() : List.of("LAB:" + code, "BIOMONITORING:" + code);
        };
    }

    public static boolean matchesTest(String ruleTest, List<String> tests) {
        return "*".equals(ruleTest) || tests.contains(ruleTest);
    }

    @SuppressWarnings("unchecked")
    public static boolean sts(String flag, Map<String, Object> detail) {
        if (!"ABNORMAL".equals(flag) || detail == null) {
            return false;
        }
        Object sts = detail.get("sts");
        if (sts instanceof Map<?, ?> m) {
            return ((Map<Object, Object>) m).values().stream().anyMatch(Boolean.TRUE::equals);
        }
        return Boolean.TRUE.equals(sts);
    }

    public static boolean spirometryDecline(Double fev1, Double fvc, Double previousFev1, double dropPct, double ratioMin) {
        if (fev1 == null || fvc == null || fvc <= 0) {
            return false;
        }
        if (fev1 / fvc < ratioMin) {
            return true;
        }
        return previousFev1 != null && previousFev1 > 0 && (previousFev1 - fev1) / previousFev1 * 100.0 >= dropPct;
    }

    public static boolean biomonitoringLimit(String value, double limit) {
        Double v = number(value);
        return v != null && v >= limit;
    }

    public static boolean vision(String flag) {
        return "ABNORMAL".equals(flag);
    }

    public static boolean flagAbnormal(String flag) {
        return flag != null && !"NORMAL".equals(flag);
    }

    /** First number in a value such as "32.5", "&gt;30" or "32 ug/dL". */
    public static Double number(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = NUMBER.matcher(value);
        return m.find() ? Double.valueOf(m.group()) : null;
    }

    public static Double number(Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return value == null ? null : number(String.valueOf(value));
    }

    public static double param(Map<String, Object> params, String key, double fallback) {
        Double v = params == null ? null : number(params.get(key));
        return v == null ? fallback : v;
    }
}
