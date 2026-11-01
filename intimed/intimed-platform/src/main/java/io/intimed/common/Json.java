package io.intimed.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Shared JSON helpers (Jackson 3). Canonical form = sorted keys, no whitespace; used for hashes and seals. */
public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    private Json() { }

    public static ObjectNode obj() { return MAPPER.createObjectNode(); }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }

    public static JsonNode parse(String s) { return MAPPER.readTree(s); }

    public static JsonNode resource(String path) {
        try (InputStream in = Json.class.getResourceAsStream(path.startsWith("/") ? path : "/" + path)) {
            if (in == null) throw new IllegalStateException("Missing resource " + path);
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String write(Object o) { return MAPPER.writeValueAsString(o); }

    public static String pretty(Object o) { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o); }

    public static <T> T convert(Object o, Class<T> type) { return MAPPER.convertValue(o, type); }

    public static JsonNode tree(Object o) { return MAPPER.valueToTree(o); }

    /** Canonical JSON: object keys sorted recursively; deterministic input for SHA-256 seals. */
    public static String canonical(JsonNode node) {
        return CANONICAL.writeValueAsString(sort(node));
    }

    private static JsonNode sort(JsonNode n) {
        if (n.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            java.util.List<String> names = new java.util.ArrayList<>(n.propertyNames());
            java.util.Collections.sort(names);
            for (String k : names) out.set(k, sort(n.get(k)));
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            n.forEach(e -> out.add(sort(e)));
            return out;
        }
        return n;
    }

    public static String text(JsonNode n, String field, String def) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? def : v.asString();
    }
}
