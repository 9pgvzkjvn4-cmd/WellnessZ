package com.wellnessz.e31.adapter.fhir;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.wellnessz.e31.support.EngineException;

import tools.jackson.databind.JsonNode;

/**
 * The FHIRPath subset used by driver selectors: {@code Type.member.member}, {@code where(path='literal')} filters and the
 * implicit flattening of arrays, e.g. {@code Observation.component.where(code.coding.code='8480-6').valueQuantity.value}.
 */
public final class MiniFhirPath {

    private static final Pattern WHERE = Pattern.compile("where\\((.+?)\\s*=\\s*'([^']*)'\\)");
    private static final Pattern MEMBER = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private MiniFhirPath() {
    }

    /** Evaluates against the given resources (the first step selects by {@code resourceType}). */
    public static List<JsonNode> evaluate(List<JsonNode> resources, String path) {
        List<String> steps = split(path);
        if (steps.isEmpty()) {
            return List.of();
        }
        List<JsonNode> current = new ArrayList<>();
        String type = steps.get(0);
        for (JsonNode r : resources) {
            if (type.equals(r.path("resourceType").asString(""))) {
                current.add(r);
            }
        }
        for (int i = 1; i < steps.size(); i++) {
            current = step(current, steps.get(i));
        }
        return current;
    }

    static List<JsonNode> step(List<JsonNode> in, String step) {
        List<JsonNode> out = new ArrayList<>();
        Matcher w = WHERE.matcher(step);
        if (w.matches()) {
            String condition = w.group(1).strip();
            String literal = w.group(2);
            for (JsonNode n : in) {
                List<JsonNode> values = new ArrayList<>(List.of(n));
                for (String s : condition.split("\\.")) {
                    values = member(values, s);
                }
                if (values.stream().anyMatch(v -> v.isValueNode() && literal.equals(v.asString()))) {
                    out.add(n);
                }
            }
            return out;
        }
        if (!MEMBER.matcher(step).matches()) {
            throw new EngineException("parse_failed", "Unsupported FHIRPath step in the driver package", true);
        }
        return member(in, step);
    }

    static List<JsonNode> member(List<JsonNode> in, String name) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode n : in) {
            JsonNode v = n.get(name);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isArray()) {
                v.forEach(out::add);
            } else {
                out.add(v);
            }
        }
        return out;
    }

    /** Splits on dots outside parentheses and quotes. */
    static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean quoted = false;
        for (char c : path.strip().toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            }
            if (c == '.' && depth == 0 && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Text of the first scalar result, {@code null} when none. */
    public static String first(List<JsonNode> resources, String path) {
        for (JsonNode n : evaluate(resources, path)) {
            if (n.isValueNode()) {
                return n.asString();
            }
        }
        return null;
    }
}
