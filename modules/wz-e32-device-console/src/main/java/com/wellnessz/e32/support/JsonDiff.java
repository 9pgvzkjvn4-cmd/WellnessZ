package com.wellnessz.e32.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Structural diff of two JSON trees as {@code [{path, from, to}]} (change history and the import diff view, E32-F06).
 * Lists are compared as values (a changed list is one entry). Pure function.
 */
public final class JsonDiff {

    private JsonDiff() {
    }

    public static List<Map<String, Object>> diff(Object from, Object to) {
        List<Map<String, Object>> out = new ArrayList<>();
        walk("", from, to, out);
        return out;
    }

    private static void walk(String path, Object a, Object b, List<Map<String, Object>> out) {
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            Set<String> keys = new LinkedHashSet<>();
            ma.keySet().forEach(k -> keys.add(String.valueOf(k)));
            mb.keySet().forEach(k -> keys.add(String.valueOf(k)));
            for (String k : keys) {
                walk(path.isEmpty() ? k : path + "." + k, ma.get(k), mb.get(k), out);
            }
            return;
        }
        if (!equal(a, b)) {
            Map<String, Object> e = new java.util.LinkedHashMap<>();
            e.put("path", path.isEmpty() ? "$" : path);
            e.put("from", a);
            e.put("to", b);
            out.add(e);
        }
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return Objects.equals(a, b);
    }
}
