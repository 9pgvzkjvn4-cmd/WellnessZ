package com.wellnessz.e32.diagnostics;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defensive PHI masking of trace excerpts shown to console roles (E32-HLR-09, contract confidentiality rule 1). The engine
 * already masks its traces; this second pass guarantees that names, employee / badge ids, dates of birth, free text and
 * values never reach a console screen even if an upstream excerpt is not masked: HL7 PID / NK1 / OBX value fields, JSON
 * members with personal or value semantics, e-mail addresses, employee-id and date patterns. Pure function.
 */
public final class TraceMasker {

    public static final String MASK = "«masked»";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern EMPLOYEE_ID = Pattern.compile("\\b[ECSNM][0-9]{3,8}\\b");
    private static final Pattern ISO_DATE = Pattern.compile("\\b(19|20)\\d{2}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])\\b");
    private static final Pattern DMY_DATE = Pattern.compile("\\b(0[1-9]|[12]\\d|3[01])[./](0[1-9]|1[0-2])[./](19|20)\\d{2}\\b");
    private static final Pattern COMPACT_DATE = Pattern.compile("\\b(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(\\d{4,6})?\\b");
    private static final Pattern JSON_PERSONAL = Pattern.compile(
            "(?i)(\"(?:name|givenName|familyName|firstName|lastName|displayName|employeeId|employeeNumber|employeeNo|patientId|badgeId|badge|"
                    + "dob|dateOfBirth|birthDate|value|valueQuantity|note|text|comment|operator|subject)\"\\s*:\\s*)(\"(?:[^\"\\\\]|\\\\.)*\"|-?[0-9.]+|\\{[^{}]*\\})");
    private static final Pattern GDT_PERSONAL = Pattern.compile("(?m)^(\\d{3}(3000|3101|3102|3103|3110|8420|6220)).*$");

    private TraceMasker() {
    }

    public static String mask(String excerpt) {
        if (excerpt == null || excerpt.isEmpty()) {
            return excerpt;
        }
        String s = maskHl7(excerpt);
        s = GDT_PERSONAL.matcher(s).replaceAll("$1" + MASK);
        s = JSON_PERSONAL.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\"" + MASK + "\""));
        s = EMAIL.matcher(s).replaceAll(MASK);
        s = EMPLOYEE_ID.matcher(s).replaceAll(MASK);
        s = ISO_DATE.matcher(s).replaceAll(MASK);
        s = DMY_DATE.matcher(s).replaceAll(MASK);
        s = COMPACT_DATE.matcher(s).replaceAll(MASK);
        return s;
    }

    /** HL7 v2: PID-2..PID-30 and NK1 entirely, OBX-5 (value) – segment ids, codes and units stay readable for support. */
    static String maskHl7(String s) {
        if (!s.contains("|")) {
            return s;
        }
        String[] segments = s.split("(\r\n|\r|\n)", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.startsWith("PID|") || seg.startsWith("NK1|") || seg.startsWith("PD1|") || seg.startsWith("NTE|")) {
                String[] f = seg.split("\\|", -1);
                for (int k = 2; k < f.length; k++) {
                    if (!f[k].isEmpty()) {
                        f[k] = MASK;
                    }
                }
                seg = String.join("|", f);
            } else if (seg.startsWith("OBX|")) {
                String[] f = seg.split("\\|", -1);
                if (f.length > 5 && !f[5].isEmpty()) {
                    f[5] = MASK;
                }
                seg = String.join("|", f);
            }
            out.append(seg);
            if (i < segments.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }
}
