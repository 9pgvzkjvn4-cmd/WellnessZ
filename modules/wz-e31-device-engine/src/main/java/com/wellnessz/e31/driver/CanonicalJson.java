package com.wellnessz.e31.driver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Canonical JSON of a driver package (contract section 6): UTF-8, object members sorted by key (code-point order), no
 * insignificant whitespace, the top-level {@code signature} member removed. Signatures are computed over these bytes.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String of(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb, true);
        return sb.toString();
    }

    private static void write(JsonNode n, StringBuilder sb, boolean top) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            sb.append("null");
        } else if (n.isObject()) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                if (!(top && "signature".equals(e.getKey()))) {
                    keys.add(e.getKey());
                }
            }
            keys.sort(null);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(k, sb);
                sb.append(':');
                write(n.get(k), sb, false);
            }
            sb.append('}');
        } else if (n.isArray()) {
            sb.append('[');
            for (int i = 0; i < n.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(n.get(i), sb, false);
            }
            sb.append(']');
        } else if (n.isString()) {
            string(n.asString(), sb);
        } else if (n.isNumber()) {
            sb.append(n.decimalValue().stripTrailingZeros().toPlainString());
        } else if (n.isBoolean()) {
            sb.append(n.asBoolean() ? "true" : "false");
        } else {
            string(n.asString(), sb);
        }
    }

    static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
