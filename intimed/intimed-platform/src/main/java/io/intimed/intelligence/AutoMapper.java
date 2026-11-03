package io.intimed.intelligence;

import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.terminology.Terminology;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Semantic auto-mapping (IM-E12-F03): proposes driver-key → template-field bindings for any template (reference,
 * imported FHIR Questionnaire, host schema) with a confidence and an explanation per suggestion. Signals: exact
 * standard-code match (LOINC / IEEE 11073 MDC / local), shared clinical concept in the reference-interval knowledge
 * base, UCUM unit compatibility (convertible), and label similarity with clinical synonyms. Suggestions below 0.5 are
 * left unbound for the integration engineer (human-in-the-loop, RR-18).
 */
@Service
public class AutoMapper {
    private static final Map<String, String> SYN = Map.ofEntries(
            Map.entry("pulse", "heart"), Map.entry("hr", "heart"), Map.entry("bpm", "heart"), Map.entry("sat", "spo2"), Map.entry("saturation", "spo2"),
            Map.entry("oxygen", "spo2"), Map.entry("sys", "systolic"), Map.entry("dia", "diastolic"), Map.entry("temp", "temperature"),
            Map.entry("resp", "respiratory"), Map.entry("rr", "respiratory"), Map.entry("wt", "weight"), Map.entry("mass", "weight"),
            Map.entry("glu", "glucose"), Map.entry("bg", "glucose"), Map.entry("hgb", "haemoglobin"), Map.entry("hemoglobin", "haemoglobin"),
            Map.entry("hb", "haemoglobin"), Map.entry("k", "potassium"), Map.entry("na", "sodium"), Map.entry("map", "mean"));
    private final DriverRegistry drivers;
    private final TemplateRegistry templates;
    private final Terminology terms;
    private final ReferenceRanges ranges;

    public AutoMapper(DriverRegistry drivers, TemplateRegistry templates, Terminology terms, ReferenceRanges ranges) {
        this.drivers = drivers;
        this.templates = templates;
        this.terms = terms;
        this.ranges = ranges;
    }

    static Set<String> words(String s) {
        Set<String> w = new HashSet<>();
        if (s == null) return w;
        for (String x : s.toLowerCase().split("[^a-z0-9]+")) {
            if (x.isBlank()) continue;
            w.add(SYN.getOrDefault(x, x));
        }
        return w;
    }

    public record Score(double value, String why) { }

    Score score(JsonNode field, JsonNode sem) {
        double s = 0;
        StringBuilder why = new StringBuilder();
        JsonNode code = field.path("code");
        for (String sys : List.of("loinc", "mdc", "local")) {
            String a = code.path(sys).asString(null), b = sem.path(sys).asString(null);
            if (a != null && a.equals(b)) { s += 0.7; why.append(sys.toUpperCase()).append(' ').append(a).append(" exact; "); break; }
        }
        JsonNode e1 = ranges.find(field, field.path("id").asString()), e2 = ranges.find(sem, sem.path("key").asString());
        if (s == 0 && e1 != null && e2 != null && e1.get("id").equals(e2.get("id"))) { s += 0.45; why.append("same clinical concept ").append(e1.get("id").asString()).append("; "); }
        boolean numericField = "quantity".equals(field.path("type").asString()) || field.has("unit");
        boolean numericSem = !"text".equals(sem.path("type").asString());
        if (numericField != numericSem) { s -= 0.3; why.append("type mismatch; "); }
        String fu = field.path("unit").asString(null), su = sem.path("ucum").asString(null);
        if (fu != null && su != null) {
            Map<String, Double> f = new java.util.HashMap<>();
            sem.path("factors").properties().forEach(x -> f.put(x.getKey(), x.getValue().asDouble()));
            if (fu.equals(su)) { s += 0.15; why.append("same unit ").append(fu).append("; "); }
            else if (terms.convert(BigDecimal.ONE, su, fu, f).isPresent()) { s += 0.1; why.append("convertible ").append(su).append(" → ").append(fu).append("; "); }
            else { s -= 0.2; why.append("unit incompatible ").append(su).append(" / ").append(fu).append("; "); }
        }
        Set<String> a = words(field.path("label").asString("") + " " + field.path("id").asString("")), b = words(sem.path("display").asString("") + " " + sem.path("key").asString(""));
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        double j = union.isEmpty() ? 0 : (double) inter.size() / union.size();
        double w = code.isMissingNode() || code.isEmpty() ? 0.55 : 0.3; // label carries more weight when the host field is uncoded
        if (j > 0) { s += w * j; why.append(String.format("label similarity %.2f", j)); }
        return new Score(Math.max(0, Math.min(1, s)), why.toString().trim());
    }

    public ObjectNode suggest(JsonNode tpl, DriverPackage d) {
        ObjectNode out = Json.obj().put("templateId", tpl.get("id").asString()).put("driver", d.ref());
        ArrayNode rows = out.putArray("suggestions");
        ObjectNode binding = Json.obj();
        ObjectNode fields = binding.putObject("fields");
        Set<String> used = new HashSet<>();
        for (JsonNode f : TemplateRegistry.fields(tpl)) {
            JsonNode best = null;
            Score bs = new Score(0, "");
            for (JsonNode s : d.semantics()) {
                Score sc = score(f, s);
                if (sc.value() > bs.value() && !used.contains(s.get("key").asString())) { best = s; bs = sc; }
            }
            ObjectNode r = rows.addObject().put("field", f.get("id").asString()).put("label", f.path("label").asString());
            if (best != null && bs.value() >= 0.5) {
                used.add(best.get("key").asString());
                fields.put(f.get("id").asString(), best.get("key").asString());
                r.put("key", best.get("key").asString()).put("confidence", Math.round(bs.value() * 100) / 100.0).put("why", bs.why()).put("status", bs.value() >= 0.8 ? "AUTO" : "REVIEW");
            } else {
                r.put("status", "UNBOUND").put("confidence", Math.round(bs.value() * 100) / 100.0).put("why", best == null ? "no candidate" : "best candidate " + best.get("key").asString() + " below 0.5: " + bs.why());
            }
        }
        out.set("binding", binding);
        JsonNode ref = d.bindings().get(tpl.get("id").asString());
        if (ref != null) {
            int agree = 0, total = 0;
            for (var e : ref.path("fields").properties()) {
                total++;
                if (e.getValue().asString().equals(fields.path(e.getKey()).asString(null))) agree++;
            }
            int extra = 0;
            for (var e : fields.properties()) if (!ref.path("fields").has(e.getKey())) extra++;
            out.putObject("againstReference").put("referenceBindings", total).put("reproduced", agree).put("extra", extra)
                    .put("recall", total == 0 ? 1 : Math.round(1000.0 * agree / total) / 1000.0);
        }
        return out;
    }

    public ObjectNode suggest(String tenant, String templateId, String driverRef) {
        return suggest(templates.require(tenant, templateId), drivers.require(tenant, driverRef));
    }

    /** Recall of auto-mapping against all reference bindings of the signed driver packages. */
    public ObjectNode benchmark(String tenant) {
        ObjectNode out = Json.obj();
        ArrayNode rows = out.putArray("pairs");
        int total = 0, agree = 0, extra = 0;
        for (DriverPackage d : drivers.all(tenant)) {
            for (String t : d.bindings().propertyNames()) {
                JsonNode tpl = templates.find(tenant, t).orElse(null);
                if (tpl == null) continue;
                ObjectNode s = suggest(tpl, d);
                JsonNode a = s.get("againstReference");
                total += a.get("referenceBindings").asInt();
                agree += a.get("reproduced").asInt();
                extra += a.get("extra").asInt();
                rows.addObject().put("driver", d.ref()).put("template", t).put("reference", a.get("referenceBindings").asInt()).put("reproduced", a.get("reproduced").asInt()).put("extra", a.get("extra").asInt());
            }
        }
        out.put("referenceBindings", total).put("reproduced", agree).put("extraSuggestions", extra).put("recall", total == 0 ? 0 : Math.round(1000.0 * agree / total) / 1000.0);
        // harder: host templates without standard codes (labels + units only), as typically imported from a host schema
        int t2 = 0, a2 = 0;
        for (DriverPackage d : drivers.all(tenant)) {
            for (String t : d.bindings().propertyNames()) {
                JsonNode tpl = templates.find(tenant, t).orElse(null);
                if (tpl == null) continue;
                ObjectNode stripped = (ObjectNode) tpl.deepCopy();
                stripped.path("sections").forEach(sec -> sec.path("fields").forEach(f -> ((ObjectNode) f).remove("code")));
                JsonNode a = suggest(stripped, d).get("againstReference");
                t2 += a.get("referenceBindings").asInt();
                a2 += a.get("reproduced").asInt();
            }
        }
        out.put("labelOnlyRecall", t2 == 0 ? 0 : Math.round(1000.0 * a2 / t2) / 1000.0);
        return out;
    }
}
