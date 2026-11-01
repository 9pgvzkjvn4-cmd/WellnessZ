package com.wellnessz.e31.support;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * PHI masking of trace excerpts and dead-letter details (device-data confidentiality rule 1): names, employee ids, badge ids,
 * dates of birth, free text and values are replaced by {@code «masked»}; only the structure (segment names, HL7 observation
 * identifiers, JSON keys, XML element names, CSV headers) stays visible to the console roles.
 */
public final class Masking {

    public static final String MASK = "«masked»";
    private static final Pattern JSON_STRING_VALUE = Pattern.compile("(\"[^\"]{1,80}\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern JSON_NUMBER_VALUE = Pattern.compile("(\"[^\"]{1,80}\"\\s*:\\s*)(-?\\d[\\d.eE+-]*|true|false)");
    private static final Pattern XML_TEXT = Pattern.compile(">([^<]+)<");
    private static final Pattern XML_ATTR = Pattern.compile("(\\s[A-Za-z_:][\\w.:-]*=)\"[^\"]*\"");
    private static final int MAX = 500;

    private Masking() {
    }

    /** Masked excerpt of a raw payload for the diagnostics trace (≤ 500 characters). */
    public static String excerpt(byte[] raw, String kind) {
        if (raw == null || raw.length == 0) {
            return "";
        }
        String text = new String(raw, 0, Math.min(raw.length, 4096), StandardCharsets.ISO_8859_1);
        String masked = switch (kind == null ? "" : kind) {
            case "HL7V2" -> hl7(text);
            case "FHIR", "BLE", "SERIAL", "WEBHOOK", "DICOMWEB", "FALLBACK", "SIMULATOR" -> json(text);
            case "POCT1A" -> xml(text);
            case "FILE" -> text.stripLeading().startsWith("<") ? xml(text) : lines(text);
            default -> MASK;
        };
        return masked.length() > MAX ? masked.substring(0, MAX) + "…" : masked;
    }

    /** HL7 v2: keeps segment names, MSH-9 and OBX-3 identifiers; every other field content is masked. */
    static String hl7(String er7) {
        StringBuilder out = new StringBuilder();
        for (String seg : er7.replace("\r\n", "\r").replace('\n', '\r').split("\r")) {
            if (seg.length() < 3) {
                continue;
            }
            String[] f = seg.split("\\|", -1);
            out.append(f[0]);
            for (int i = 1; i < f.length; i++) {
                out.append('|');
                boolean keep = "MSH".equals(f[0]) && (i == 1 || i == 8 || i == 11) || "OBX".equals(f[0]) && (i == 2 || i == 3);
                out.append(f[i].isEmpty() ? "" : keep ? f[i] : MASK);
            }
            out.append("\\r ");
        }
        return out.toString().strip();
    }

    static String json(String text) {
        String s = JSON_STRING_VALUE.matcher(text).replaceAll("$1\"" + MASK + "\"");
        return JSON_NUMBER_VALUE.matcher(s).replaceAll("$1\"" + MASK + "\"");
    }

    static String xml(String text) {
        String s = XML_TEXT.matcher(text).replaceAll(m -> m.group(1).isBlank() ? m.group(0) : ">" + MASK + "<");
        return XML_ATTR.matcher(s).replaceAll("$1\"" + MASK + "\"");
    }

    /** CSV / GDT: first line (header / field ids) kept, values masked. */
    static String lines(String text) {
        String[] lines = text.replace("\r\n", "\n").split("\n");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.matches("\\d{7}.*")) {
                out.append(l, 0, 7).append(MASK);
            } else if (i == 0) {
                out.append(l);
            } else {
                out.append(MASK);
            }
            out.append(" | ");
        }
        return out.toString().strip();
    }

    /** Free-text detail of a failure: never the payload, only the first 200 characters of an engine message. */
    public static String detail(String message) {
        if (message == null) {
            return null;
        }
        String s = message.replaceAll("[0-9]{4,}", MASK);
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
