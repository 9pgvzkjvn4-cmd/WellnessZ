package com.wellnessz.e34.signing;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

/**
 * Canonical form of a driver-package manifest for signing (contract §6 / §15.5): UTF-8 JSON, object keys sorted
 * lexicographically, no insignificant whitespace, numbers as written (decimal values kept with their scale), and the top-level
 * {@code signature} member removed.
 */
public final class Canonicaliser {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();

    private Canonicaliser() {
    }

    public static byte[] canonical(String json) {
        return canonical(MAPPER.readTree(json));
    }

    public static byte[] canonical(JsonNode manifest) {
        StringBuilder sb = new StringBuilder();
        write(manifest, sb, true);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    private static void write(JsonNode node, StringBuilder sb, boolean top) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            sb.append("null");
        } else if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames());
            Collections.sort(names);
            sb.append('{');
            boolean first = true;
            for (String name : names) {
                if (top && "signature".equals(name)) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(MAPPER.writeValueAsString(StringNode.valueOf(name))).append(':');
                write(node.get(name), sb, false);
            }
            sb.append('}');
        } else if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(node.get(i), sb, false);
            }
            sb.append(']');
        } else if (node.isBigDecimal()) {
            sb.append(node.decimalValue().toPlainString());
        } else {
            sb.append(MAPPER.writeValueAsString(node));
        }
    }
}
