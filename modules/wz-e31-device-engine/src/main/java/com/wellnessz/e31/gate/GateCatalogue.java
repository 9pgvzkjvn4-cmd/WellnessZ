package com.wellnessz.e31.gate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The quality-gate catalogue (contract section 10). Gate set and parameters per family need clinical SME sign-off (E31-BL-017). */
public final class GateCatalogue {

    public record Definition(String code, String description, List<String> families, List<String> parameters) {
    }

    public static final Map<String, Definition> GATES;

    static {
        Map<String, Definition> m = new LinkedHashMap<>();
        put(m, "CALIBRATION", "Device calibration in date and in service (E14 equipment register); unknown device warns", List.of("ALL"), List.of());
        put(m, "QC_DAILY", "Daily / interval QC check passed (audiometer biological, 3-L syringe, breath accuracy, fit tester, BP validation)",
                List.of("AUDIOMETRY", "SPIROMETRY", "DRUG_ALCOHOL", "RESP_ERGONOMICS", "VITALS"), List.of("checkType"));
        put(m, "QC_LOT", "Reagent / QC lot in date with a passing QC run", List.of("POC"), List.of());
        put(m, "ATS_ERS_GRADE", "ATS/ERS session grade at or above the minimum; F or no acceptable manoeuvre blocks", List.of("SPIROMETRY"),
                List.of("MIN"));
        put(m, "PREDICTED_VALUES", "GLI-2012 predicted values, % predicted and LLN computed", List.of("SPIROMETRY"), List.of());
        put(m, "AUDIO_TEST_RETEST", "1 kHz re-test within tolerance in each ear", List.of("AUDIOMETRY"), List.of("TOLERANCE_DB"));
        put(m, "AUDIO_AMBIENT_NOISE", "Ambient noise within ANSI S3.1 limits during the audiogram", List.of("AUDIOMETRY"), List.of());
        put(m, "BP_CUFF_VALIDATION", "ISO 81060-2 validated device; cuff size recorded and matching the arm circumference", List.of("VITALS"),
                List.of());
        put(m, "FIT_EXERCISES_COMPLETE", "All exercises of the fit-test protocol present (OSHA 1910.134 App. A)", List.of("RESP_ERGONOMICS"),
                List.of());
        put(m, "PLAUSIBILITY", "Physiological ranges, cross-checks and outliers versus the employee's last three values", List.of("ALL"),
                List.of());
        put(m, "CLOCK_SKEW", "Device / edge clock skew within tolerance (corrected); excessive skew holds the result", List.of("ALL"), List.of());
        put(m, "CONSENT", "Active wearable or kiosk consent of the employee", List.of("WEARABLE", "VITALS"), List.of());
        put(m, "ATTESTATION", "Manual entry attests model, serial and calibration", List.of("ALL"), List.of());
        put(m, "OCR_CONFIDENCE", "OCR fields below the confidence threshold are left for the clinician; verification mandatory", List.of("ALL"),
                List.of());
        put(m, "VERIFY_BEFORE_POST", "Clinician verification before the value becomes part of the record", List.of("ALL"), List.of());
        GATES = Map.copyOf(m);
    }

    private GateCatalogue() {
    }

    private static void put(Map<String, Definition> m, String code, String d, List<String> families, List<String> params) {
        m.put(code, new Definition(code, d, families, params));
    }

    /** Gate code without its parameter ({@code QC_DAILY:SPIRO_SYRINGE_3L} → {@code QC_DAILY}). */
    public static String code(String gate) {
        int i = gate.indexOf(':');
        return i < 0 ? gate : gate.substring(0, i);
    }

    /** Parameter part ({@code ATS_ERS_GRADE:MIN=C} → {@code MIN=C}), {@code null} when none. */
    public static String parameter(String gate) {
        int i = gate.indexOf(':');
        return i < 0 ? null : gate.substring(i + 1);
    }

    public static boolean isKnown(String gate) {
        return gate != null && GATES.containsKey(code(gate));
    }

    /** Named parameter ({@code MIN=C} → {@code C}); a bare parameter is returned for any name. */
    public static String param(String gate, String name, String fallback) {
        String p = parameter(gate);
        if (p == null) {
            return fallback;
        }
        for (String part : p.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].strip().equalsIgnoreCase(name)) {
                return kv[1].strip();
            }
            if (kv.length == 1) {
                return kv[0].strip();
            }
        }
        return fallback;
    }
}
