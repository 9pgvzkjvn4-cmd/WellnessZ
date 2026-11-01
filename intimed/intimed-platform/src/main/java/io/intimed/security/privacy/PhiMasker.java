package io.intimed.security.privacy;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * PHI masking for traces, diagnostics, audit details and evidence packs (SC-08, SC-15). HL7 PID/NK1/PV1 identifying
 * fields, JSON identity keys and free-text identifiers are replaced; measurements and codes are kept so that a support
 * engineer can still diagnose a mapping problem.
 */
public final class PhiMasker {
    private static final Set<String> KEYS = Set.of("subject", "subjectId", "patientId", "patient", "name", "familyName",
            "givenName", "dob", "dateOfBirth", "birthDate", "mrn", "employeeId", "badge", "phone", "email", "address",
            "nhsNumber", "ssn", "displayName", "operatorName");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern DOB = Pattern.compile("\\b(19|20)\\d{2}-?\\d{2}-?\\d{2}\\b");

    private PhiMasker() { }

    public static String maskHl7(String er7) {
        StringBuilder out = new StringBuilder();
        for (String seg : er7.replace("\r\n", "\r").replace('\n', '\r').split("\r")) {
            if (seg.startsWith("PID|") || seg.startsWith("NK1|") || seg.startsWith("PV1|") || seg.startsWith("GT1|")) {
                String[] f = seg.split("\\|", -1);
                for (int i = 2; i < f.length; i++) if (!f[i].isEmpty()) f[i] = "***";
                seg = String.join("|", f);
            }
            out.append(seg).append('\r');
        }
        return out.toString();
    }

    public static JsonNode maskJson(JsonNode n) {
        if (n == null) return null;
        JsonNode copy = n.deepCopy();
        walk(copy);
        return copy;
    }

    private static void walk(JsonNode n) {
        if (n instanceof ObjectNode o) {
            for (String k : new java.util.ArrayList<>(o.propertyNames())) {
                JsonNode v = o.get(k);
                if (KEYS.contains(k) && (v.isString() || v.isNumber())) o.put(k, "***");
                else walk(v);
            }
        } else if (n != null && n.isArray()) {
            n.forEach(PhiMasker::walk);
        }
    }

    /** Masks any payload: HL7 if it looks like ER7, JSON if parseable, otherwise e-mail / date patterns. */
    public static String mask(String payload) {
        if (payload == null) return null;
        String t = payload.trim();
        if (t.startsWith("MSH|") || t.contains("\rPID|") || t.contains("\nPID|")) return maskHl7(payload);
        if (t.startsWith("{") || t.startsWith("[")) {
            try { return Json.write(maskJson(Json.parse(t))); } catch (RuntimeException ignored) { /* fall through */ }
        }
        return DOB.matcher(EMAIL.matcher(payload).replaceAll("***@***")).replaceAll("****-**-**");
    }
}
