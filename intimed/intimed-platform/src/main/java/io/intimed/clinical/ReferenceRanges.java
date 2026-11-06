package io.intimed.clinical;

import io.intimed.common.Json;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Reference-interval knowledge base (IM-E14): entries matched to template fields by LOINC / MDC / local code or field id,
 * partitioned by age, sex, pregnancy/trimester and context modifiers (fasting, site altitude, regulation, respirator type).
 * Produces a resolved interval and an HL7 ObservationInterpretation flag (N, L, H, LL, HH) plus an optional grade
 * (e.g. ACC/AHA BP category, WHO hearing grade, AASM AHI severity). Defaults must be confirmed by each organisation.
 */
@Service
public class ReferenceRanges {
    public static final String INTERPRETATION_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
    private final JsonNode kb = Json.resource("clinical/reference-ranges.json");
    /** Tenant overlay source (IM-E14-BL-011): tenant → ACTIVE overlay {version, entries:{entryId:[ranges]}} or null. */
    private volatile java.util.function.Function<String, JsonNode> overlays = t -> null;

    /** Registered by the interval-governance service; kept as a hook so the knowledge base stays usable stand-alone. */
    public void overlaySource(java.util.function.Function<String, JsonNode> source) { this.overlays = source == null ? t -> null : source; }

    /**
     * The entry as it applies to a tenant: the tenant's ACTIVE, four-eyes-approved and EP28-verified overlay replaces the
     * default partitions of that entry (modifiers and context notes are kept); otherwise the default entry.
     */
    public JsonNode effective(JsonNode e, String tenant) {
        if (e == null || tenant == null) return e;
        JsonNode ov;
        try { ov = overlays.apply(tenant); } catch (RuntimeException ex) { ov = null; }
        if (ov == null || !ov.path("entries").has(e.get("id").asString())) return e;
        ObjectNode c = (ObjectNode) e.deepCopy();
        ArrayNode rs = Json.arr();
        for (JsonNode r : ov.path("entries").get(e.get("id").asString())) {
            ObjectNode rr = (ObjectNode) r.deepCopy();
            if (!rr.has("categories")) for (JsonNode d : e.get("ranges")) {
                boolean overlap = d.get("ageFrom").asDouble() < rr.path("ageTo").asDouble(200) && rr.path("ageFrom").asDouble(0) < d.get("ageTo").asDouble();
                String ds = d.path("sex").asString("any"), os = rr.path("sex").asString("any");
                if (overlap && d.has("categories") && (ds.equals(os) || "any".equals(ds))) { rr.set("categories", d.get("categories")); break; }
            }
            rs.add(rr);
        }
        c.set("ranges", rs);
        c.put("source", "tenant overlay v" + ov.path("version").asInt() + (ov.hasNonNull("source") ? " – " + ov.get("source").asString() : ""));
        c.put("overlayVersion", ov.path("version").asInt()).put("overlayId", ov.path("id").asString());
        return c;
    }

    public Resolved resolve(JsonNode e, Demographics d, String tenant) { return resolve(effective(e, tenant), d); }

    public JsonNode knowledgeBase() { return kb; }
    public JsonNode entries() { return kb.get("entries"); }

    public JsonNode entry(String id) {
        for (JsonNode e : entries()) if (e.get("id").asString().equals(id)) return e;
        return null;
    }

    /** Finds the entry for a template field (definition with code) or a driver semantic (key + codes). */
    public JsonNode find(JsonNode fieldDef, String fieldId) {
        JsonNode code = fieldDef == null ? null : fieldDef.has("code") ? fieldDef.get("code") : fieldDef;
        for (JsonNode e : entries()) {
            JsonNode m = e.get("match");
            if (code != null) {
                for (String sys : List.of("loinc", "mdc", "local")) {
                    String v = code.path(sys).asString(null);
                    if (v != null) for (JsonNode x : m.path(sys)) if (x.asString().equals(v)) return e;
                }
                String local = code.path("local").asString(null);
                if (local != null) for (JsonNode x : m.path("localPrefix")) {
                    String px = x.asString();
                    if (local.startsWith(px) && local.length() > px.length() && Character.isDigit(local.charAt(px.length()))) return e;
                }
            }
        }
        if (fieldId != null) for (JsonNode e : entries()) for (JsonNode x : e.get("match").path("fieldIds")) if (x.asString().equals(fieldId)) return e;
        return null;
    }

    public String ageBand(Double age) {
        if (age == null) return "Unknown age";
        for (JsonNode b : kb.get("ageBands")) if (age >= b.get(1).asDouble() && age < b.get(2).asDouble()) return b.get(0).asString();
        return "Unknown age";
    }

    public record Resolved(String entryId, String name, String unit, BigDecimal low, BigDecimal high, BigDecimal criticalLow, BigDecimal criticalHigh,
                           String partition, String source, String type, JsonNode categories, List<String> notes, boolean gap) {
        public String text() {
            if (gap) return "no reference interval for this partition";
            String r = low != null && high != null ? low.stripTrailingZeros().toPlainString() + "–" + high.stripTrailingZeros().toPlainString()
                    : low != null ? "≥ " + low.stripTrailingZeros().toPlainString() : high != null ? "≤ " + high.stripTrailingZeros().toPlainString() : "—";
            return r + " " + unit;
        }
    }

    public Resolved resolve(JsonNode e, Demographics d) {
        List<String> notes = new ArrayList<>();
        JsonNode best = null;
        int bestScore = -1;
        for (JsonNode r : e.get("ranges")) {
            if (d.ageYears() != null && (d.ageYears() < r.get("ageFrom").asDouble() || d.ageYears() >= r.get("ageTo").asDouble())) continue;
            if (d.ageYears() == null && r.get("ageFrom").asDouble() > 0 && r.get("ageTo").asDouble() < 200 && r.get("ageFrom").asDouble() < 18) continue;
            String sex = r.path("sex").asString("any");
            if (!"any".equals(sex) && !sex.equals(d.sex())) continue;
            boolean preg = r.path("pregnant").asBoolean(false);
            if (preg && !(d.pregnant() && d.trimester() != null && d.trimester() == r.path("trimester").asInt())) continue;
            int score = ("any".equals(sex) ? 0 : 2) + (preg ? 4 : 0);
            if (score > bestScore) { best = r; bestScore = score; }
        }
        if (d.ageYears() == null) notes.add("age unknown – adult interval assumed where available");
        String src = e.path("source").asString();
        if (best == null) {
            notes.add(e.path("gapNote").asString("no interval defined for " + d.describe()));
            return new Resolved(e.get("id").asString(), e.get("name").asString(), e.get("unit").asString(), null, null, null, null, d.describe(), src, e.path("type").asString("normal"), null, notes, true);
        }
        BigDecimal low = dec(best, "low"), high = dec(best, "high"), cl = dec(best, "criticalLow"), ch = dec(best, "criticalHigh");
        for (JsonNode m : e.path("modifiers")) {
            String p = m.get("param").asString();
            boolean applies = switch (p) {
                case "siteAltitudeM" -> d.siteAltitudeM() != null && d.siteAltitudeM() >= m.path("gte").asInt();
                case "fasting" -> d.fasting() == m.path("eq").asBoolean();
                case "respiratorType" -> m.path("eq").asString().equals(d.respiratorType());
                default -> false;
            };
            if (applies) {
                JsonNode s = m.get("set");
                if (s.has("low")) low = s.get("low").decimalValue();
                if (s.has("high")) high = s.get("high").decimalValue();
                if (s.has("criticalLow")) cl = s.get("criticalLow").decimalValue();
                notes.add(m.path("note").asString());
            }
        }
        if (e.has("context")) {
            JsonNode c = e.get("context");
            boolean ok = false;
            for (JsonNode v : c.path("values")) ok |= v.asString().equals(d.regulation());
            if (!ok) notes.add("regulatory thresholds shown for information – " + c.path("note").asString());
        }
        if (best.has("note")) notes.add(best.get("note").asString());
        String part = (best.path("pregnant").asBoolean(false) ? "pregnancy T" + best.path("trimester").asInt() + ", " : "")
                + ("any".equals(best.path("sex").asString("any")) ? "" : best.get("sex").asString() + ", ") + ageText(best);
        return new Resolved(e.get("id").asString(), e.get("name").asString(), e.get("unit").asString(), low, high, cl, ch, part, src, e.path("type").asString("normal"),
                best.path("categories").isMissingNode() ? null : best.get("categories"), notes, false);
    }

    private String ageText(JsonNode r) {
        double f = r.get("ageFrom").asDouble(), t = r.get("ageTo").asDouble();
        if (f == 0 && t >= 200) return "all ages";
        if (t >= 200) return "age ≥ " + fmtAge(f);
        return "age " + fmtAge(f) + "–<" + fmtAge(t);
    }

    private static String fmtAge(double y) { return y < 1 ? Math.round(y * 365) + " d" : (y == Math.floor(y) ? String.valueOf((int) y) : String.valueOf(y)) + " y"; }

    private static BigDecimal dec(JsonNode n, String f) { return n.hasNonNull(f) ? n.get(f).decimalValue() : null; }

    public record Interpretation(String code, String display, String grade) { }

    public Interpretation interpret(BigDecimal v, Resolved r) {
        if (r == null || r.gap() || v == null) return null;
        String code;
        if (r.criticalLow() != null && v.compareTo(r.criticalLow()) < 0) code = "LL";
        else if (r.criticalHigh() != null && v.compareTo(r.criticalHigh()) >= 0) code = "HH";
        else if (r.low() != null && v.compareTo(r.low()) < 0) code = "L";
        else if (r.high() != null && v.compareTo(r.high()) > 0) code = "H";
        else code = "N";
        String grade = null;
        if (r.categories() != null) {
            for (JsonNode c : r.categories()) {
                BigDecimal lo = c.get(1).isNull() ? null : c.get(1).decimalValue(), hi = c.get(2).isNull() ? null : c.get(2).decimalValue();
                if ((lo == null || v.compareTo(lo) >= 0) && (hi == null || v.compareTo(hi) < 0)) { grade = c.get(0).asString(); break; }
            }
        }
        String display = switch (code) { case "LL" -> "Critical low"; case "HH" -> "Critical high"; case "L" -> "Low"; case "H" -> "High"; default -> "Normal"; };
        return new Interpretation(code, display, grade);
    }

    /** Annotates a mapped template field value in place (referenceRange, interpretation, grade, partition, source). */
    public ObjectNode annotate(ObjectNode fieldValue, JsonNode fieldDef, String fieldId, Demographics d) { return annotate(fieldValue, fieldDef, fieldId, d, null); }

    /** Tenant-aware annotation: the tenant's ACTIVE overlay is used first (source "tenant overlay vN"), defaults otherwise. */
    public ObjectNode annotate(ObjectNode fieldValue, JsonNode fieldDef, String fieldId, Demographics d, String tenant) {
        if (!fieldValue.path("value").isNumber()) return fieldValue;
        JsonNode e = effective(find(fieldDef, fieldId), tenant);
        if (e == null) return fieldValue;
        Resolved r = resolve(e, d);
        ObjectNode rr = fieldValue.putObject("referenceRange").put("entry", r.entryId()).put("text", r.text()).put("partition", r.partition())
                .put("ageBand", ageBand(d.ageYears())).put("source", r.source()).put("type", r.type()).put("gap", r.gap());
        if (r.low() != null) rr.put("low", r.low());
        if (r.high() != null) rr.put("high", r.high());
        if (r.criticalLow() != null) rr.put("criticalLow", r.criticalLow());
        if (r.criticalHigh() != null) rr.put("criticalHigh", r.criticalHigh());
        if (!r.notes().isEmpty()) rr.set("notes", Json.tree(r.notes()));
        if (e.has("overlayVersion")) rr.put("overlayVersion", e.get("overlayVersion").asInt()).put("overlayId", e.path("overlayId").asString());
        Interpretation i = interpret(fieldValue.get("value").decimalValue(), r);
        if (i != null) {
            fieldValue.putObject("interpretation").put("code", i.code()).put("display", i.display()).put("system", INTERPRETATION_SYSTEM);
            if (i.grade() != null) fieldValue.put("grade", i.grade());
        }
        return fieldValue;
    }

    public ArrayNode summary() {
        ArrayNode a = Json.arr();
        for (JsonNode e : entries()) a.addObject().put("id", e.get("id").asString()).put("name", e.get("name").asString()).put("unit", e.get("unit").asString())
                .put("source", kb.get("sources").path(e.get("source").asString().split(" / ")[0]).asString(e.get("source").asString())).put("partitions", e.get("ranges").size())
                .put("gapNote", e.path("gapNote").asString("")).set("ranges", e.get("ranges"));
        return a;
    }
}
