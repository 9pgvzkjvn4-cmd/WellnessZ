package com.wellnessz.e32.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small helpers for the JSON documents the console keeps (configuration data, driver manifests, option schemas): typed
 * access to {@code Map} / {@code List} trees as produced by Jackson, dotted paths, deep copies and hashing. Pure functions.
 */
public final class Docs {

    private Docs() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    public static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Collection<?> c) {
            for (Object x : c) {
                if (x != null) {
                    out.add(String.valueOf(x));
                }
            }
        }
        return out;
    }

    public static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    public static boolean bool(Object o, boolean fallback) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    public static Integer integer(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && s.matches("-?\\d{1,9}")) {
            return Integer.valueOf(s);
        }
        return null;
    }

    /** Value at a dotted path ({@code mllp.port}); {@code null} when any segment is missing. */
    public static Object at(Map<String, Object> root, String path) {
        Object cur = root;
        for (String seg : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(seg);
        }
        return cur;
    }

    /** Sets a value at a dotted path, creating intermediate objects. */
    public static void put(Map<String, Object> root, String path, Object value) {
        String[] segs = path.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            Object next = cur.get(segs[i]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(segs[i], next);
            }
            cur = map(next);
        }
        cur.put(segs[segs.length - 1], value);
    }

    /** Deep copy of a JSON-like tree (maps, lists, scalars). */
    public static Object copy(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), copy(v)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object x : l) {
                out.add(copy(x));
            }
            return out;
        }
        return o;
    }

    public static Map<String, Object> copyMap(Map<String, Object> m) {
        return map(copy(m));
    }

    /** Deep merge: values of {@code overlay} win; nested objects are merged, lists replaced. */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = copyMap(base);
        if (overlay == null) {
            return out;
        }
        overlay.forEach((k, v) -> {
            Object existing = out.get(k);
            if (existing instanceof Map<?, ?> && v instanceof Map<?, ?>) {
                out.put(k, merge(map(existing), map(v)));
            } else {
                out.put(k, copy(v));
            }
        });
        return out;
    }

    /** Ordered map literal: {@code Docs.of("a", 1, "b", 2)}; {@code null} values are omitted (contract: null fields omitted). */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put(String.valueOf(kv[i]), kv[i + 1]);
            }
        }
        return m;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static List<String> csv(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        for (String p : s.split(",")) {
            if (!p.isBlank()) {
                out.add(p.strip());
            }
        }
        return out;
    }

    public static String joinCsv(Collection<String> values) {
        return values == null ? "" : String.join(",", values);
    }
}
