package io.intimed.web;

import io.intimed.clinical.DeltaCheck;
import io.intimed.clinical.Demographics;
import io.intimed.clinical.IntervalGovernance;
import io.intimed.clinical.PlatformCategories;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.engine.sim.Simulator;
import io.intimed.intelligence.AutoMapper;
import io.intimed.intelligence.CohortSimulator;
import io.intimed.intelligence.DeviceIdentifier;
import io.intimed.intelligence.DriftMonitor;
import io.intimed.intelligence.IntegrationProfiles;
import io.intimed.intelligence.TerminologyService;
import io.intimed.intelligence.ValidationEngine;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Intelligence studio API (IntiMed 2.0 – IM-E11…IM-E15): validation, identification, auto-mapping, cohorts, reference
 * intervals, drift; IntiMed 2.1 adds derived values and delta flags in the studio (IM-E14-BL-010/012), tenant interval
 * governance (IM-E14-BL-011), the drift alert workflow (IM-E15-BL-003), trajectories (IM-E13-BL-005) and the terminology
 * adapter status/lookup (IM-E12-BL-010).
 */
@RestController
@RequestMapping("/api/intelligence")
public class IntelligenceController {
    private static final int MAX_PAYLOAD = 256 * 1024;
    private final ReferenceRanges ranges;
    private final PlatformCategories categories;
    private final ValidationEngine validation;
    private final CohortSimulator cohorts;
    private final DeviceIdentifier identifier;
    private final IntegrationProfiles profiles;
    private final AutoMapper mapper;
    private final DriftMonitor drift;
    private final DriverRegistry drivers;
    private final TemplateRegistry templates;
    private final Pipeline pipeline;
    private final AuditService audit;
    private final IntervalGovernance intervals;
    private final TerminologyService terminology;
    private final DocumentStore store;

    public IntelligenceController(ReferenceRanges ranges, PlatformCategories categories, ValidationEngine validation, CohortSimulator cohorts, DeviceIdentifier identifier,
                                  IntegrationProfiles profiles, AutoMapper mapper, DriftMonitor drift, DriverRegistry drivers, TemplateRegistry templates, Pipeline pipeline, AuditService audit,
                                  IntervalGovernance intervals, TerminologyService terminology, DocumentStore store) {
        this.intervals = intervals;
        this.terminology = terminology;
        this.store = store;
        this.ranges = ranges;
        this.categories = categories;
        this.validation = validation;
        this.cohorts = cohorts;
        this.identifier = identifier;
        this.profiles = profiles;
        this.mapper = mapper;
        this.drift = drift;
        this.drivers = drivers;
        this.templates = templates;
        this.pipeline = pipeline;
        this.audit = audit;
    }

    private static String t() { return Current.tenant(); }

    private static void engineer() {
        Current.require(Role.INTEGRATION_DEV, Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.PARTNER_DEV, Role.REGULATORY, Role.TENANT_ADMIN, Role.PHYSICIAN);
    }

    private static String payload(JsonNode b) {
        String p = b.path("payload").asString(null);
        if (p == null || p.isBlank()) throw ApiException.bad("payload-required", "payload is required");
        if (p.length() > MAX_PAYLOAD) throw ApiException.bad("payload-too-large", "payload exceeds 256 KiB");
        return p;
    }

    // ---------------------------------------------------------------- knowledge
    @GetMapping("/reference-ranges")
    public ObjectNode referenceRanges() {
        ObjectNode o = Json.obj().put("version", ranges.knowledgeBase().path("version").asString()).put("disclaimer", ranges.knowledgeBase().path("disclaimer").asString());
        o.set("sources", ranges.knowledgeBase().get("sources"));
        o.set("ageBands", ranges.knowledgeBase().get("ageBands"));
        o.set("entries", ranges.summary());
        return o;
    }

    @PostMapping("/reference-ranges/evaluate")
    public ObjectNode evaluate(@RequestBody JsonNode b) {
        JsonNode e = ranges.entry(b.path("entry").asString(""));
        if (e == null) throw ApiException.notFound("reference entry " + b.path("entry").asString(""));
        Demographics d = Demographics.of(b.path("demographics"));
        ReferenceRanges.Resolved r = ranges.resolve(e, d);
        ObjectNode o = Json.obj().put("entry", r.entryId()).put("name", r.name()).put("unit", r.unit()).put("text", r.text()).put("partition", r.partition())
                .put("ageBand", ranges.ageBand(d.ageYears())).put("source", r.source()).put("gap", r.gap()).put("demographics", d.describe());
        if (r.low() != null) o.put("low", r.low());
        if (r.high() != null) o.put("high", r.high());
        if (r.criticalLow() != null) o.put("criticalLow", r.criticalLow());
        if (r.criticalHigh() != null) o.put("criticalHigh", r.criticalHigh());
        o.set("notes", Json.tree(r.notes()));
        if (b.path("value").isNumber() || b.path("value").isString()) {
            ReferenceRanges.Interpretation i = ranges.interpret(new BigDecimal(b.get("value").asString()), r);
            if (i != null) {
                o.putObject("interpretation").put("code", i.code()).put("display", i.display()).put("system", ReferenceRanges.INTERPRETATION_SYSTEM);
                if (i.grade() != null) o.put("grade", i.grade());
            }
        }
        return o;
    }

    @GetMapping("/categories") public JsonNode categories() { return categories.all(); }
    @GetMapping("/device-types") public ObjectNode deviceTypes() { return profiles.taxonomy(); }
    @GetMapping("/profiles/{modelId}") public ObjectNode profile(@PathVariable String modelId) { return profiles.forModel(t(), modelId); }

    // ---------------------------------------------------------------- identification & mapping
    @PostMapping("/identify")
    public ObjectNode identify(@RequestBody JsonNode b) {
        engineer();
        String p = payload(b);
        ObjectNode r = identifier.identify(t(), p);
        audit.record(t(), Current.actor(), "intelligence.identify", "payload/" + io.intimed.common.Hashing.sha256(p).substring(0, 16),
                Map.of("verdict", r.path("verdict").asString(), "best", r.at("/bestMatch/driver").asString("")));
        return r;
    }

    @GetMapping("/identify/benchmark") public ObjectNode identifyBenchmark() { engineer(); return identifier.benchmark(t()); }

    @PostMapping("/automap")
    public ObjectNode automap(@RequestBody JsonNode b) { engineer(); return mapper.suggest(t(), b.path("templateId").asString(), b.path("driver").asString()); }

    @GetMapping("/automap/benchmark") public ObjectNode automapBenchmark() { engineer(); return mapper.benchmark(t()); }

    // ---------------------------------------------------------------- cohorts
    @GetMapping("/cohorts") public JsonNode cohorts() { return cohorts.profiles(); }

    @GetMapping("/cohorts/{profile}/drivers")
    public List<String> cohortDrivers(@PathVariable String profile) {
        return cohorts.driversFor(t(), cohorts.profile(profile)).stream().map(DriverPackage::ref).toList();
    }

    @PostMapping("/cohorts/simulate")
    public ObjectNode simulate(@RequestBody JsonNode b) {
        engineer();
        List<String> art = new ArrayList<>();
        b.path("artefacts").forEach(a -> { if (CohortSimulator.ARTEFACTS.contains(a.asString())) art.add(a.asString()); });
        return cohorts.simulate(t(), b.path("driver").asString(), b.path("profile").asString("adult-normal"), art, b.path("seed").asLong(42));
    }

    @GetMapping("/cohorts/run-all") public ObjectNode runAll() { engineer(); return cohorts.runAll(t()); }

    // ---------------------------------------------------------------- validation studio
    /**
     * Validation studio: payload (pasted, or simulated for a cohort) → dry-run pipeline → mapped template → layered
     * validation for the chosen healthcare-platform category → output exactly as the host would receive it.
     */
    @PostMapping("/validate")
    public ObjectNode validate(@RequestBody JsonNode b) {
        engineer();
        DriverPackage d = drivers.require(t(), b.path("driver").asString());
        String templateId = b.path("templateId").asString(null);
        if (templateId == null) templateId = d.bindings().propertyNames().iterator().next();
        if (!d.bindings().has(templateId)) throw ApiException.bad("not-bound", "driver " + d.ref() + " has no binding for " + templateId);
        String category = b.path("category").asString(null);
        // RR-17: the category recorded on a configuration is used when the caller validates "as configured"
        if (category == null && b.hasNonNull("configId")) category = store.get(t(), "config", b.get("configId").asString()).map(c -> c.path("platformCategory").asString(null)).orElse(null);
        if (category == null) { List<String> c = categories.forTemplate(templateId); category = c.isEmpty() ? null : c.get(0); }
        boolean derive = b.path("derivedValues").asBoolean(false);
        Demographics demo;
        String p;
        String source;
        if (b.hasNonNull("profile")) {
            ObjectNode sim = cohorts.simulate(t(), d.ref(), b.get("profile").asString(), List.of(), b.path("seed").asLong(42));
            p = sim.get("payload").asString();
            demo = Demographics.of(cohorts.profile(b.get("profile").asString()).get("demographics"));
            source = "cohort " + b.get("profile").asString();
        } else if (b.hasNonNull("payload")) {
            p = payload(b);
            demo = Demographics.of(b.path("demographics"));
            source = "pasted payload";
        } else {
            p = Simulator.render(d, Simulator.values(d), "MRN-VAL-001", Instant.now(), d.tests().path("serial").asString("SIM-1"));
            demo = Demographics.of(b.path("demographics"));
            source = "driver golden session";
        }
        String tplId = templateId;
        ObjectNode dry = pipeline.dryRun(t(), d, p, List.of(templateId), id -> templates.find(t(), id).orElse(null), demo, derive);
        JsonNode tpl = templates.require(t(), templateId);
        ObjectNode fields = Json.obj();
        ObjectNode derivedBlock = Json.obj();
        for (JsonNode set : dry.path("sets")) for (JsonNode m : set.path("mapped")) if (tplId.equals(m.path("templateId").asString())) {
            m.path("fields").properties().forEach(e -> fields.set(e.getKey(), e.getValue()));
            m.path("derived").properties().forEach(e -> derivedBlock.set(e.getKey(), e.getValue()));
        }
        // delta flags (IM-E14-BL-012) against previous values supplied by the caller: {"previous": {"potassium": {"value": 4.1, "minutesAgo": 120}}}
        if (b.path("previous").isObject()) fields.properties().forEach(e -> {
            String entryId = e.getValue().at("/referenceRange/entry").asString(null);
            JsonNode prev = entryId == null ? null : b.path("previous").get(entryId);
            JsonNode entry = entryId == null ? null : ranges.entry(entryId);
            if (prev == null || entry == null || !prev.path("value").isNumber() || !e.getValue().path("value").isNumber()) return;
            Instant now = Instant.now(), pat = now.minusSeconds(prev.path("minutesAgo").asLong(60) * 60);
            int window = DeltaCheck.windowHours(entry, null);
            DeltaCheck.Result dr = DeltaCheck.check(entry, prev.get("value").decimalValue(), pat, e.getValue().get("value").decimalValue(), now, window);
            if (dr != null) ((ObjectNode) e.getValue()).set("delta", dr.toJson(prev.get("value").decimalValue(), pat, window));
        });
        ObjectNode r = validation.validate(t(), tpl, fields, category, demo, b.path("context"));
        r.put("derivedValuesEnabled", derive);
        if (!derivedBlock.isEmpty()) r.set("derived", derivedBlock);
        r.put("driver", d.ref()).put("source", source).put("payload", p).put("dryRunStatus", dry.path("status").asString());
        if (dry.has("error")) r.put("dryRunError", dry.get("error").asString());
        List<String> blocked = new ArrayList<>();
        for (JsonNode set : dry.path("sets")) for (JsonNode m : set.path("mapped")) m.path("blocked").forEach(x -> blocked.add(x.asString()));
        r.set("blocked", Json.tree(blocked));
        r.set("categoriesForTemplate", Json.tree(categories.forTemplate(templateId)));
        audit.record(t(), Current.actor(), "intelligence.validate", "template/" + templateId, Map.of("driver", d.ref(), "category", String.valueOf(category), "verdict", r.path("verdict").asString()));
        return r;
    }

    // ---------------------------------------------------------------- drift (IM-E15-F01, workflow IM-E15-BL-003)
    private static void biomed() { Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE, Role.TENANT_ADMIN, Role.REGULATORY); }

    @PostMapping("/drift/analyse")
    public ObjectNode analyse() { biomed(); return drift.analyse(t(), Current.actor()); }

    @GetMapping("/drift/alerts") public List<ObjectNode> alerts() { return drift.alerts(t()); }

    /** Calibration records of the alert's configuration (to link on acknowledge / resolve). */
    @GetMapping("/drift/alerts/{id}/calibrations")
    public JsonNode alertCalibrations(@PathVariable String id) {
        biomed();
        ObjectNode a = store.require(t(), DriftMonitor.KIND, id);
        return store.get(t(), "config", a.path("configId").asString()).map(c -> c.path("calibration").path("history")).orElse(Json.arr());
    }

    @PostMapping("/drift/alerts/{id}/ack")
    public ObjectNode ackAlert(@PathVariable String id, @RequestBody JsonNode b) {
        biomed();
        return drift.acknowledge(t(), id, b.path("note").asString(null), b.path("calibrationAt").asString(null), Current.actor());
    }

    @PostMapping("/drift/alerts/{id}/resolve")
    public ObjectNode resolveAlert(@PathVariable String id, @RequestBody JsonNode b) {
        biomed();
        return drift.resolve(t(), id, b.path("note").asString(null), b.path("calibrationAt").asString(null), Current.actor());
    }

    // ---------------------------------------------------------------- tenant interval governance (IM-E14-BL-011)
    @GetMapping("/intervals") public List<ObjectNode> overlays() { engineer(); return intervals.list(t()); }
    @GetMapping("/intervals/{id}") public ObjectNode overlay(@PathVariable String id) { engineer(); return intervals.get(t(), id); }

    @PostMapping("/intervals/import/csv")
    public ObjectNode importCsv(@RequestBody JsonNode b) { return intervals.importCsv(t(), b.path("csv").asString(null), Current.actor()); }

    @PostMapping("/intervals/import/observation-definition")
    public ObjectNode importOd(@RequestBody JsonNode b) { return intervals.importObservationDefinitions(t(), b, Current.actor()); }

    @PostMapping("/intervals/{id}/verify")
    public ObjectNode verifyOverlay(@PathVariable String id, @RequestBody JsonNode b) {
        List<BigDecimal> results = new ArrayList<>();
        for (JsonNode v : b.path("results")) {
            try { results.add(new BigDecimal(v.asString())); } catch (NumberFormatException e) { throw ApiException.bad("result", "non-numeric reference result"); }
        }
        if (results.size() > 10_000) throw ApiException.bad("too-many", "at most 10 000 results");
        return intervals.verify(t(), id, b.path("entry").asString(), b.path("index").asInt(0), results, Current.actor());
    }

    @PostMapping("/intervals/{id}/approve")
    public ObjectNode approveOverlay(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        return intervals.approve(t(), id, b == null ? null : b.path("comment").asString(null), Current.actor());
    }

    @PostMapping("/intervals/{id}/activate") public ObjectNode activateOverlay(@PathVariable String id) { return intervals.activate(t(), id, Current.actor()); }

    @PostMapping("/intervals/{id}/retire")
    public ObjectNode retireOverlay(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        return intervals.retire(t(), id, b == null ? null : b.path("reason").asString(null), Current.actor());
    }

    // ---------------------------------------------------------------- trajectories (IM-E13-BL-005)
    /**
     * Renders a profile trajectory as N payloads at an interval and returns per-step flags plus a NEWS2-style aggregate.
     * {@code stream=true} (DEVICE_ADMIN) also feeds each payload, re-timed to now, into a PUBLISHED configuration of the
     * same driver through the live pipeline (the same path the capture component uses).
     */
    @PostMapping("/cohorts/trajectory")
    public ObjectNode trajectory(@RequestBody JsonNode b) {
        engineer();
        boolean stream = b.path("stream").asBoolean(false);
        ObjectNode cfg = null;
        if (stream) {
            Current.require(Role.DEVICE_ADMIN);
            cfg = store.require(t(), "config", b.path("configId").asString(""));
            if (!"PUBLISHED".equals(cfg.path("status").asString())) throw ApiException.conflict("not-published", "streaming needs a PUBLISHED configuration");
        }
        String driver = b.path("driver").asString(cfg == null ? null : cfg.path("driver").asString());
        if (cfg != null && driver != null && !driver.equals(cfg.path("driver").asString())) throw ApiException.bad("driver-mismatch", "configuration " + cfg.get("id").asString() + " uses driver " + cfg.path("driver").asString());
        int steps = Math.min(b.path("steps").asInt(0), 200);
        String subject = b.path("subject").asString(null);
        if (subject != null && !subject.matches("[A-Za-z0-9._-]{1,64}")) throw ApiException.bad("subject", "invalid subject identifier");
        Instant start = stream ? Instant.now() : Instant.parse("2026-03-02T06:00:00Z");
        ObjectNode r = cohorts.trajectory(t(), driver, b.path("profile").asString(""), steps, b.path("intervalMinutes").asInt(30), b.path("seed").asLong(42), start, subject);
        if (stream) {
            ArrayNode res = r.putArray("stream");
            int i = 0;
            for (JsonNode row : r.path("series")) {
                // live quality gates hold device clocks > 5 min off: streamed steps are re-timed to the moment of sending
                Map<String, BigDecimal> ov = new java.util.LinkedHashMap<>();
                row.path("values").properties().forEach(e -> ov.put(e.getKey(), e.getValue().decimalValue()));
                String payload = cohorts.simulate(t(), r.get("driver").asString(), r.get("profile").asString(), List.of(), b.path("seed").asLong(42) + i, ov, Instant.now().plusSeconds(i), subject).get("payload").asString();
                PipelineResult pr = pipeline.process(Envelope.live(t(), null, cfg.get("id").asString(), cfg.path("option").asString(), payload, "trajectory-stream"));
                res.addObject().put("step", row.path("step").asInt()).put("status", pr.status).put("ack", pr.ackCode);
                i++;
            }
            audit.record(t(), Current.actor(), "intelligence.trajectory.stream", "config/" + cfg.get("id").asString(), Map.of("profile", r.get("profile").asString(), "steps", i));
        }
        return r;
    }

    // ---------------------------------------------------------------- terminology adapter (IM-E12-BL-010)
    @GetMapping("/terminology/status") public ObjectNode terminologyStatus() { engineer(); return terminology.status(); }

    @PostMapping("/terminology/lookup")
    public ObjectNode terminologyLookup(@RequestBody JsonNode b) {
        engineer();
        String system = b.path("system").asString(""), code = b.path("code").asString("");
        if (system.isBlank() || code.isBlank()) throw ApiException.bad("system-code", "system and code are required");
        ObjectNode o = Json.obj().put("system", system).put("code", code).put("enabled", terminology.enabled()).put("knownSystem", TerminologyService.known(system));
        terminology.lookup(system, code).ifPresent(c -> o.put("display", c.display()));
        terminology.validateCode(system, code).ifPresent(v -> o.put("valid", v));
        ArrayNode tr = o.putArray("translations");
        for (TerminologyService.Mapping m : terminology.translate(system, code, b.path("targetSystem").asString("http://loinc.org")))
            tr.addObject().put("system", m.system()).put("code", m.code()).put("display", m.display()).put("equivalence", m.equivalence());
        return o;
    }
}
