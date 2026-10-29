package com.wellnessz.e03.results;

import java.util.Locale;

/**
 * Abnormal flag of a laboratory result: the sender's interpretation (HL7 table 0078 or words) wins; otherwise a numeric
 * value is compared with the reference range ({@code "3.5-5.0"}, {@code "<5.7"}, {@code ">60"}). Parsing is done by hand –
 * no regular expressions on external input.
 */
public final class ResultFlags {

    private ResultFlags() {
    }

    public static String flag(String interpretation, String value, String referenceRange) {
        String fromInterpretation = fromInterpretation(interpretation);
        if (fromInterpretation != null) {
            return fromInterpretation;
        }
        return fromRange(value, referenceRange);
    }

    static String fromInterpretation(String interpretation) {
        if (interpretation == null || interpretation.isBlank()) {
            return null;
        }
        return switch (interpretation.trim().toUpperCase(Locale.ROOT)) {
            case "N", "NORMAL" -> "NORMAL";
            case "L", "LOW" -> "LOW";
            case "H", "HIGH" -> "HIGH";
            case "LL", "HH", "AA", "CRITICAL", "PANIC" -> "CRITICAL";
            case "A", "ABNORMAL", "POS", "POSITIVE", "DETECTED" -> "ABNORMAL";
            default -> null;
        };
    }

    static String fromRange(String value, String range) {
        Double v = number(value);
        if (v == null || range == null || range.isBlank()) {
            return "NORMAL";
        }
        String r = range.trim();
        if (r.startsWith("<=") || r.startsWith("<")) {
            Double max = number(r.substring(r.startsWith("<=") ? 2 : 1));
            return max == null ? "NORMAL" : (r.startsWith("<=") ? v > max : v >= max) ? "HIGH" : "NORMAL";
        }
        if (r.startsWith(">=") || r.startsWith(">")) {
            Double min = number(r.substring(r.startsWith(">=") ? 2 : 1));
            return min == null ? "NORMAL" : (r.startsWith(">=") ? v < min : v <= min) ? "LOW" : "NORMAL";
        }
        int dash = r.indexOf('-', 1); // skip a leading minus sign of the lower bound
        if (dash > 0) {
            Double low = number(r.substring(0, dash));
            Double high = number(r.substring(dash + 1));
            if (low != null && v < low) {
                return "LOW";
            }
            if (high != null && v > high) {
                return "HIGH";
            }
        }
        return "NORMAL";
    }

    /** Numeric value of a result, or {@code null} when it is not a plain number. */
    public static Double numeric(String s) {
        return number(s);
    }

    static Double number(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty() || t.length() > 30) {
            return null;
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
