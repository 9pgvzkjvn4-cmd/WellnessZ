package com.wellnessz.e34.secrets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * Secret scanner shared by the console import, driver validation and the security test pack (contract §15.6): a member whose
 * key looks like a credential must hold a reference ({@code devsec:} / {@code vault:}), never a literal value. Findings carry
 * the JSON path only – never the value.
 */
public final class SecretScanner {

    public static final Pattern SENSITIVE_KEY = Pattern.compile("(?i)(password|secret|token|api[-_]?key|private[-_]?key|credential)");
    public static final Pattern REFERENCE = Pattern.compile("^(devsec|vault):.+");

    /** Literal-looking credentials inside free text (PEM private keys, bearer tokens, AWS-style keys). */
    private static final Pattern EMBEDDED = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----|(?i)bearer\\s+[A-Za-z0-9._\\-]{20,}|AKIA[0-9A-Z]{16}");

    public record Finding(String path, String rule) {
    }

    private SecretScanner() {
    }

    public static List<Finding> scan(JsonNode node) {
        List<Finding> out = new ArrayList<>();
        walk(node, "$", null, out);
        return out;
    }

    private static void walk(JsonNode node, String path, String key, List<Finding> out) {
        if (node == null || out.size() >= 200) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                walk(e.getValue(), path + "." + e.getKey(), e.getKey(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", key, out);
            }
        } else if (node.isString()) {
            String v = node.asString();
            if (key != null && SENSITIVE_KEY.matcher(key).find() && !v.isEmpty() && !REFERENCE.matcher(v).matches()) {
                out.add(new Finding(path, "literal_secret"));
            } else if (EMBEDDED.matcher(v).find()) {
                out.add(new Finding(path, "embedded_credential"));
            }
        } else if (node.isNumber() && key != null && SENSITIVE_KEY.matcher(key).find()) {
            out.add(new Finding(path, "literal_secret"));
        }
    }
}
