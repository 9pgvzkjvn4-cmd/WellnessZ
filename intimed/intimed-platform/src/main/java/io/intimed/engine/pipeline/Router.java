package io.intimed.engine.pipeline;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.clinical.Demographics;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.engine.terminology.Terminology;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateAdapters;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routing with many-to-many template binding resolution and provenance fan-out (IM-E01-F07 / IM-E01-BL-023): each
 * binding of the configuration maps driver keys to template fields; values are converted into the field's unit (RR-06 –
 * unconvertible values are blocked, never posted), delivered into a template instance (sandbox for harness runs, shadow
 * for parallel runs, live for published devices) and rendered by the template's adapters.
 */
@Component
public class Router {
    private final DocumentStore store;
    private final TemplateRegistry templates;
    private final TemplateAdapters adapters;
    private final Terminology terms;
    private final ReferenceRanges ranges;

    public Router(DocumentStore store, TemplateRegistry templates, TemplateAdapters adapters, Terminology terms, ReferenceRanges ranges) {
        this.store = store;
        this.templates = templates;
        this.adapters = adapters;
        this.terms = terms;
        this.ranges = ranges;
    }

    /**
     * Demographics for reference-interval partitioning (IM-E14): subject registry (birthDate, sex, pregnancy) at the
     * observation's effective time, plus configuration context parameters (site altitude, regulation, respirator type).
     */
    public Demographics demographics(String tenant, JsonNode config, JsonNode obs) {
        String ref = obs.path("subject").path("ref").asString(null);
        JsonNode s = ref == null ? null : store.get(tenant, "subject", ref).orElse(null);
        Instant at = Instant.parse(obs.path("effectiveTime").asString(Instant.now().toString()));
        return Demographics.fromSubject(s, at, null).withContext(config.path("params"));
    }

    public static boolean flaggingEnabled(JsonNode config) { return config.path("params").path("referenceRangeFlagging").asBoolean(true); }

    /**
     * Pure mapping of one measurement onto one template field (no persistence) – shared by live routing and the dry-run
     * validation studio: unit conversion to the field unit (RR-06), rounding, and reference-interval annotation.
     * Returns null and adds a reason to {@code blocked} when the value cannot be posted.
     */
    public ObjectNode mapField(JsonNode m, JsonNode def, String fieldId, Demographics demo, boolean flag, List<String> blocked) {
        ObjectNode fv = Json.obj();
        if (m.get("value").isNumber()) {
            String target = def.path("unit").asString(m.path("unit").asString(null));
            Map<String, Double> factors = new HashMap<>();
            m.path("factors").properties().forEach(f -> factors.put(f.getKey(), f.getValue().asDouble()));
            var conv = terms.convert(m.get("value").decimalValue(), m.path("unit").asString(null), target, factors);
            if (conv.isEmpty()) { blocked.add(fieldId + ": no conversion " + m.path("unit").asString() + " → " + target); return null; }
            BigDecimal v = conv.get();
            if (def.has("decimals")) v = v.setScale(def.get("decimals").asInt(), java.math.RoundingMode.HALF_UP);
            fv.put("value", v).put("unit", target);
            if (!target.equals(m.path("unit").asString(target))) fv.put("convertedFrom", m.get("value").asString() + " " + m.path("unit").asString());
            if (flag && demo != null) ranges.annotate(fv, def, fieldId, demo);
        } else {
            fv.put("value", m.get("value").asString());
        }
        fv.put("sourceKey", m.get("key").asString());
        if (m.has("flags") && !m.get("flags").isEmpty()) fv.set("flags", m.get("flags"));
        return fv;
    }

    /** Dry-run mapping of an observation into a template through a binding (validation studio, cohort simulator). */
    public ObjectNode dryMap(JsonNode binding, JsonNode tpl, JsonNode obs, Demographics demo) {
        Map<String, JsonNode> byKey = new HashMap<>();
        obs.get("measurements").forEach(m -> byKey.put(m.get("key").asString(), m));
        ObjectNode out = Json.obj().put("templateId", tpl.get("id").asString()).put("templateName", tpl.path("name").asString());
        ObjectNode fields = out.putObject("fields");
        List<String> blocked = new java.util.ArrayList<>();
        binding.path("fields").properties().forEach(e -> {
            JsonNode m = byKey.get(e.getValue().asString());
            if (m == null) return;
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            if (def == null) { blocked.add(e.getKey() + ": not in template"); return; }
            ObjectNode fv = mapField(m, def, e.getKey(), demo, true, blocked);
            if (fv != null) fields.set(e.getKey(), fv);
        });
        out.set("blocked", Json.tree(blocked));
        return out;
    }

    public static String instanceKind(String mode) {
        return switch (mode) { case "TEST" -> "sandbox-instance"; case "SHADOW" -> "shadow"; default -> "template-instance"; };
    }

    public void route(String tenant, JsonNode config, ObjectNode obs, String mode, String runId, PipelineResult result) {
        ArrayNode routing = obs.putArray("routing");
        Map<String, JsonNode> byKey = new HashMap<>();
        obs.get("measurements").forEach(m -> byKey.put(m.get("key").asString(), m));
        Demographics demo = demographics(tenant, config, obs);
        boolean flag = flaggingEnabled(config);
        for (JsonNode b : config.path("bindings")) {
            String tplId = b.get("templateId").asString();
            JsonNode tpl = templates.require(tenant, tplId);
            String kind = instanceKind(mode);
            String instId = instanceId(config, obs, tpl, runId);
            ObjectNode inst = store.get(tenant, kind, instId).orElseGet(() -> newInstance(instId, tpl, config, obs, mode, runId));
            ObjectNode fields = (ObjectNode) inst.get("fields");
            ObjectNode r = routing.addObject().put("templateId", tplId).put("instanceId", instId);
            ArrayNode filled = r.putArray("fields");
            ArrayNode blocked = r.putArray("blocked");
            b.path("fields").properties().forEach(e -> {
                JsonNode m = byKey.get(e.getValue().asString());
                if (m == null) return;
                JsonNode def = TemplateRegistry.field(tpl, e.getKey());
                if (def == null) { blocked.add(e.getKey() + ": not in template"); return; }
                List<String> why = new java.util.ArrayList<>();
                ObjectNode fv = mapField(m, def, e.getKey(), demo, flag, why);
                if (fv == null) {
                    why.forEach(blocked::add);
                    result.routingErrors.add(tplId + "/" + e.getKey() + " unit " + m.path("unit").asString() + " not convertible to " + def.path("unit").asString());
                    return;
                }
                fv.put("observationId", obs.get("id").asString());
                if ("CONTINUOUS".equals(tpl.path("captureMode").asString())) {
                    ArrayNode series = fields.has(e.getKey()) && fields.get(e.getKey()).has("series") ? (ArrayNode) fields.get(e.getKey()).get("series") : Json.arr();
                    ObjectNode pt = series.addObject().put("t", obs.get("effectiveTime").asString());
                    pt.set("v", fv.get("value"));
                    if (fv.has("interpretation")) pt.put("f", fv.at("/interpretation/code").asString());
                    fv.set("series", series);
                }
                fields.set(e.getKey(), fv);
                filled.add(e.getKey());
            });
            inst.put("filedAt", Instant.now().toString()).put("status", "FILED");
            if (flag) inst.putObject("demographics").put("ageBand", ranges.ageBand(demo.ageYears())).put("partition", demo.describe());
            ArrayNode deliveries = inst.putArray("deliveries");
            for (JsonNode a : tpl.path("adapters")) {
                ObjectNode out = adapters.render(a.asString(), tpl, inst);
                deliveries.addObject().put("adapter", a.asString()).put("ack", out.path("ack").asString()).put("at", Instant.now().toString());
            }
            store.put(tenant, kind, instId, inst, "FILED", "TEST".equals(mode) ? runId : tplId);
            r.put("delivered", filled.size() > 0);
            if (!result.instanceIds.contains(instId)) result.instanceIds.add(instId);
        }
    }

    private String instanceId(JsonNode config, ObjectNode obs, JsonNode tpl, String runId) {
        if ("CONTINUOUS".equals(tpl.path("captureMode").asString())) {
            int mins = config.path("intervalMinutes").asInt(5);
            Instant t = Instant.parse(obs.get("effectiveTime").asString()).truncatedTo(ChronoUnit.HOURS);
            return Hashing.stableId("ti", tpl.get("id").asString() + obs.path("subject").path("ref").asString() + t + "/" + mins + "/" + (runId == null ? "" : runId));
        }
        return Hashing.stableId("ti", tpl.get("id").asString() + obs.get("id").asString() + (runId == null ? "" : runId));
    }

    private ObjectNode newInstance(String id, JsonNode tpl, JsonNode config, ObjectNode obs, String mode, String runId) {
        ObjectNode i = Json.obj().put("id", id).put("templateId", tpl.get("id").asString()).put("templateVersion", tpl.path("version").asString())
                .put("templateName", tpl.path("name").asString()).put("subject", obs.path("subject").path("ref").asString())
                .put("effectiveTime", obs.get("effectiveTime").asString()).put("deviceSerial", obs.path("deviceSerial").asString(""))
                .put("configId", config.get("id").asString()).put("mode", mode).put("runId", runId).put("createdAt", Instant.now().toString());
        i.putObject("fields");
        i.putObject("provenance").put("driver", obs.at("/driver/ref").asString()).put("configHash", obs.path("configHash").asString())
                .put("configId", config.get("id").asString()).put("endpoint", obs.path("endpointId").asString(""));
        return i;
    }

    /** Adapter rendering on demand (API / evidence pack). */
    public ObjectNode render(String tenant, String adapter, JsonNode instance) {
        return adapters.render(adapter, templates.require(tenant, instance.get("templateId").asString()), instance);
    }

    public List<String> adapterNames(String tenant, String tplId) {
        List<String> l = new java.util.ArrayList<>();
        templates.require(tenant, tplId).path("adapters").forEach(a -> l.add(a.asString()));
        return l;
    }
}
