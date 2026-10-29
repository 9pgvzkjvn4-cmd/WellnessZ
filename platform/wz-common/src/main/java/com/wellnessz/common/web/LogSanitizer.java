package com.wellnessz.common.web;

/** Neutralises user-controlled values before logging (CWE-117 log injection). */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    public static String clean(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value).replaceAll("[\\r\\n\\t]", "_");
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
