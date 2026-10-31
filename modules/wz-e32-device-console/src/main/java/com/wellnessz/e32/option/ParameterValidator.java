package com.wellnessz.e32.option;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.wellnessz.e32.driver.DriverPackageRules;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.FieldIssue;

/**
 * Validator for the subset of JSON Schema 2020-12 used by the option packs: {@code type}, {@code enum}, {@code const},
 * {@code minimum} / {@code maximum}, {@code minLength} / {@code maxLength}, {@code pattern}, {@code required},
 * {@code properties}, {@code additionalProperties:false}, {@code items}, {@code minItems} / {@code maxItems} and the formats
 * {@code secret} (value must be a vault reference), {@code hostname} and {@code iana-time-zone}. Also derives the schema
 * defaults and the secret field paths. Pure functions.
 */
public final class ParameterValidator {

    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9-]{1,63}");

    private ParameterValidator() {
    }

    public static List<FieldIssue> validate(Map<String, Object> schema, Object value) {
        List<FieldIssue> out = new ArrayList<>();
        check(schema, value, "", out);
        return out;
    }

    private static void check(Map<String, Object> s, Object v, String path, List<FieldIssue> out) {
        String field = path.isEmpty() ? "parameters" : path;
        if (v == null) {
            return;
        }
        if (s.containsKey("const") && !equalsJson(s.get("const"), v)) {
            out.add(new FieldIssue(field, "const", "must be " + s.get("const")));
            return;
        }
        List<Object> en = Docs.list(s.get("enum"));
        if (s.containsKey("enum") && en.stream().noneMatch(x -> equalsJson(x, v))) {
            out.add(new FieldIssue(field, "enum", "must be one of " + en));
            return;
        }
        String type = Docs.str(s.get("type"));
        if (type != null && !typeMatches(type, v)) {
            out.add(new FieldIssue(field, "type", "must be of type " + type));
            return;
        }
        if (v instanceof Number n) {
            Object min = s.get("minimum");
            Object max = s.get("maximum");
            if (min instanceof Number mn && n.doubleValue() < mn.doubleValue()) {
                out.add(new FieldIssue(field, "minimum", "must be at least " + mn));
            }
            if (max instanceof Number mx && n.doubleValue() > mx.doubleValue()) {
                out.add(new FieldIssue(field, "maximum", "must be at most " + mx));
            }
        }
        if (v instanceof String str) {
            Integer minLen = Docs.integer(s.get("minLength"));
            Integer maxLen = Docs.integer(s.get("maxLength"));
            if (minLen != null && str.length() < minLen) {
                out.add(new FieldIssue(field, "minLength", "must have at least " + minLen + " characters"));
            }
            if (maxLen != null && str.length() > maxLen) {
                out.add(new FieldIssue(field, "maxLength", "must have at most " + maxLen + " characters"));
            }
            String pattern = Docs.str(s.get("pattern"));
            if (pattern != null && !matches(pattern, str)) {
                out.add(new FieldIssue(field, "pattern", "has an invalid format"));
            }
            String format = Docs.str(s.get("format"));
            if ("secret".equals(format) && !DriverPackageRules.isReference(str)) {
                out.add(new FieldIssue(field, "secret_inline", "secrets are write-only: send the value in 'secrets', never in parameters"));
            } else if ("hostname".equals(format) && !hostname(str)) {
                out.add(new FieldIssue(field, "hostname", "must be a valid host name"));
            } else if ("iana-time-zone".equals(format) && !validZone(str)) {
                out.add(new FieldIssue(field, "time_zone", "must be an IANA time zone"));
            }
        }
        if (v instanceof Map<?, ?> raw) {
            Map<String, Object> obj = Docs.map(raw);
            Map<String, Object> props = Docs.map(s.get("properties"));
            for (String req : Docs.strings(s.get("required"))) {
                if (obj.get(req) == null) {
                    out.add(new FieldIssue(join(path, req), "required", "is required"));
                }
            }
            if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                for (String k : obj.keySet()) {
                    if (!props.containsKey(k)) {
                        out.add(new FieldIssue(join(path, k), "unknown_parameter", "is not a parameter of this option"));
                    }
                }
            }
            props.forEach((k, sub) -> check(Docs.map(sub), obj.get(k), join(path, k), out));
        }
        if (v instanceof List<?> raw) {
            List<Object> arr = Docs.list(raw);
            Integer minItems = Docs.integer(s.get("minItems"));
            Integer maxItems = Docs.integer(s.get("maxItems"));
            if (minItems != null && arr.size() < minItems) {
                out.add(new FieldIssue(field, "minItems", "needs at least " + minItems + " entries"));
            }
            if (maxItems != null && arr.size() > maxItems) {
                out.add(new FieldIssue(field, "maxItems", "allows at most " + maxItems + " entries"));
            }
            if (s.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < arr.size(); i++) {
                    check(Docs.map(items), arr.get(i), field + "[" + i + "]", out);
                }
            }
        }
    }

    /** Schema defaults as a parameter document (object defaults merged with property defaults). */
    public static Map<String, Object> defaults(Map<String, Object> schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        Docs.map(schema.get("properties")).forEach((k, raw) -> {
            Map<String, Object> sub = Docs.map(raw);
            Object d = sub.get("default");
            if ("object".equals(sub.get("type"))) {
                Map<String, Object> nested = defaults(sub);
                Map<String, Object> merged = Docs.merge(nested, Docs.map(d));
                if (!merged.isEmpty()) {
                    out.put(k, merged);
                }
            } else if (d != null) {
                out.put(k, Docs.copy(d));
            }
        });
        return out;
    }

    /** Dotted paths of {@code format: secret} members (write-only, stored through DeviceSecretStore). */
    public static List<String> secretPaths(Map<String, Object> schema) {
        List<String> out = new ArrayList<>();
        collectSecrets(schema, "", out);
        return out;
    }

    private static void collectSecrets(Map<String, Object> s, String path, List<String> out) {
        if ("secret".equals(s.get("format"))) {
            out.add(path);
        }
        Docs.map(s.get("properties")).forEach((k, sub) -> collectSecrets(Docs.map(sub), join(path, k), out));
    }

    private static boolean typeMatches(String type, Object v) {
        return switch (type) {
            case "object" -> v instanceof Map<?, ?>;
            case "array" -> v instanceof List<?>;
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "integer" -> v instanceof Integer || v instanceof Long || v instanceof Short
                    || v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && !(v instanceof Float);
            case "number" -> v instanceof Number;
            default -> true;
        };
    }

    private static boolean equalsJson(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return a != null && a.equals(b);
    }

    private static boolean matches(String pattern, String value) {
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    static boolean hostname(String host) {
        if (host.isEmpty() || host.length() > 253 || host.startsWith(".") || host.endsWith(".")) {
            return false;
        }
        for (String label : host.split("\\.")) {
            if (!LABEL.matcher(label).matches() || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    private static boolean validZone(String zone) {
        try {
            ZoneId.of(zone);
            return zone.contains("/") || "UTC".equals(zone);
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static String join(String path, String key) {
        return path.isEmpty() ? key : path + "." + key;
    }
}
