package io.intimed.engine.parse;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSON payloads: P6 vendor cloud APIs / webhooks (RPM kits, wearables, fit-test clouds) and P11 SDC metric reports
 * (BICEPS JSON rendering – SDC-ready stub). Selector = JSON pointer with array filters, e.g.
 * {@code /measures[type=systolic]/value}, {@code /metrics[handle=hr.sat]/value}.
 */
public class JsonPathParser implements PayloadParser {
    private static final Pattern STEP = Pattern.compile("([^\\[]*)\\[([A-Za-z0-9_.-]+)=([^\\]]*)]");
    private final String type;

    public JsonPathParser(String type) { this.type = type; }

    @Override public String type() { return type; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        JsonNode root;
        try { root = Json.parse(payload); } catch (RuntimeException e) { throw new ParseException("payload is not valid JSON"); }
        if (g.has("requireField") && select(root, g.get("requireField").asString()) == null) throw new ParseException("required element missing");
        List<JsonNode> sets = new ArrayList<>();
        if (g.has("each")) { JsonNode arr = select(root, g.get("each").asString()); if (arr != null) arr.forEach(sets::add); }
        else sets.add(root);
        List<Parsed> out = new ArrayList<>();
        for (JsonNode s : sets) {
            Parsed p = new Parsed();
            for (var e : g.path("identity").properties()) {
                String sel = e.getValue().asString();
                JsonNode v = sel.startsWith("$") ? select(root, sel.substring(1)) : select(s, sel);
                if (v != null && !v.isNull()) p.id(e.getKey(), v.asString());
            }
            for (JsonNode f : g.path("fields")) {
                JsonNode v = select(s, f.get("path").asString());
                if (v == null || v.isNull()) continue;
                String unit = f.path("unit").asString(null);
                if (f.has("unitPath")) { JsonNode u = select(s, f.get("unitPath").asString()); if (u != null && !u.isNull()) unit = u.asString(); }
                p.val(f.get("key").asString(), v.asString(), unit);
            }
            if (!p.values.isEmpty()) out.add(p);
        }
        if (out.isEmpty()) throw new ParseException("no mapped values in JSON payload");
        return out;
    }

    public static JsonNode select(JsonNode root, String path) {
        JsonNode cur = root;
        for (String step : path.split("/")) {
            if (step.isEmpty()) continue;
            if (cur == null) return null;
            Matcher m = STEP.matcher(step);
            if (m.matches()) {
                JsonNode arr = m.group(1).isEmpty() ? cur : cur.get(m.group(1));
                JsonNode hit = null;
                if (arr != null && arr.isArray()) for (JsonNode e : arr) if (m.group(3).equals(e.path(m.group(2)).asString(""))) { hit = e; break; }
                cur = hit;
            } else if (cur.isArray() && step.matches("\\d+")) {
                cur = cur.get(Integer.parseInt(step));
            } else {
                cur = cur.get(step);
            }
        }
        return cur;
    }
}
