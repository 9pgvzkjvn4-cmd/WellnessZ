package io.intimed.web;

import io.intimed.clinical.Demographics;
import io.intimed.clinical.PlatformCategories;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.sim.Simulator;
import io.intimed.intelligence.AutoMapper;
import io.intimed.intelligence.CohortSimulator;
import io.intimed.intelligence.DeviceIdentifier;
import io.intimed.intelligence.DriftMonitor;
import io.intimed.intelligence.IntegrationProfiles;
import io.intimed.intelligence.ValidationEngine;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.templates.TemplateRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Intelligence studio API (IntiMed 2.0 – IM-E11…IM-E15): validation, identification, auto-mapping, cohorts, reference intervals, drift. */
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

    public IntelligenceController(ReferenceRanges ranges, PlatformCategories categories, ValidationEngine validation, CohortSimulator cohorts, DeviceIdentifier identifier,
                                  IntegrationProfiles profiles, AutoMapper mapper, DriftMonitor drift, DriverRegistry drivers, TemplateRegistry templates, Pipeline pipeline, AuditService audit) {
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
        if (category == null) { List<String> c = categories.forTemplate(templateId); category = c.isEmpty() ? null : c.get(0); }
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
        ObjectNode dry = pipeline.dryRun(t(), d, p, List.of(templateId), id -> templates.find(t(), id).orElse(null), demo);
        JsonNode tpl = templates.require(t(), templateId);
        ObjectNode fields = Json.obj();
        for (JsonNode set : dry.path("sets")) for (JsonNode m : set.path("mapped")) if (tplId.equals(m.path("templateId").asString())) m.path("fields").properties().forEach(e -> fields.set(e.getKey(), e.getValue()));
        ObjectNode r = validation.validate(tpl, fields, category, demo, b.path("context"));
        r.put("driver", d.ref()).put("source", source).put("payload", p).put("dryRunStatus", dry.path("status").asString());
        if (dry.has("error")) r.put("dryRunError", dry.get("error").asString());
        List<String> blocked = new ArrayList<>();
        for (JsonNode set : dry.path("sets")) for (JsonNode m : set.path("mapped")) m.path("blocked").forEach(x -> blocked.add(x.asString()));
        r.set("blocked", Json.tree(blocked));
        r.set("categoriesForTemplate", Json.tree(categories.forTemplate(templateId)));
        audit.record(t(), Current.actor(), "intelligence.validate", "template/" + templateId, Map.of("driver", d.ref(), "category", String.valueOf(category), "verdict", r.path("verdict").asString()));
        return r;
    }

    // ---------------------------------------------------------------- drift
    @PostMapping("/drift/analyse")
    public ObjectNode analyse() { Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE, Role.TENANT_ADMIN, Role.REGULATORY); return drift.analyse(t(), Current.actor()); }

    @GetMapping("/drift/alerts") public List<ObjectNode> alerts() { return drift.alerts(t()); }
}
