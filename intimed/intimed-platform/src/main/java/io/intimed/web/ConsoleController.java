package io.intimed.web;

import io.intimed.catalogue.CatalogueService;
import io.intimed.catalogue.RegisterFeedService;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.console.OptionPacks;
import io.intimed.console.intelligence.DocAssistant;
import io.intimed.console.intelligence.KnowledgeBase;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.driver.DriverValidator;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.harness.EvidenceSealer;
import io.intimed.harness.HarnessService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.store.DocumentStore;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Console API (IM-E02/IM-E03/IM-E04 – architecture §11.1 "Console API" and "Harness API"). */
@RestController
@RequestMapping("/api")
public class ConsoleController {
    private final CatalogueService catalogue;
    private final RegisterFeedService feeds;
    private final ConfigService configs;
    private final OptionPacks packs;
    private final DriverRegistry drivers;
    private final HarnessService harness;
    private final EvidenceSealer sealer;
    private final DocAssistant docs;
    private final KnowledgeBase kb;
    private final DocumentStore store;
    private final Pipeline pipeline;
    private final AuditService audit;

    public ConsoleController(CatalogueService catalogue, RegisterFeedService feeds, ConfigService configs, OptionPacks packs, DriverRegistry drivers, HarnessService harness,
                             EvidenceSealer sealer, DocAssistant docs, KnowledgeBase kb, DocumentStore store, Pipeline pipeline, AuditService audit) {
        this.catalogue = catalogue;
        this.feeds = feeds;
        this.configs = configs;
        this.packs = packs;
        this.drivers = drivers;
        this.harness = harness;
        this.sealer = sealer;
        this.docs = docs;
        this.kb = kb;
        this.store = store;
        this.pipeline = pipeline;
        this.audit = audit;
    }

    private static String t() { return Current.tenant(); }
    private static String a() { return Current.actor(); }

    // ---------------------------------------------------------------- catalogue
    @GetMapping("/catalogue/stats") public Map<String, Object> stats() { return catalogue.stats(); }
    @GetMapping("/catalogue/families") public List<ObjectNode> families() { return catalogue.families(); }
    @GetMapping("/catalogue/fda-codes") public Object codes() { return catalogue.codes().values(); }
    @GetMapping("/catalogue/models")
    public List<ObjectNode> search(@RequestParam(required = false) String q, @RequestParam(required = false) String family, @RequestParam(required = false) String option,
                                   @RequestParam(required = false) String setting, @RequestParam(required = false) String code, @RequestParam(defaultValue = "100") int limit) {
        return catalogue.search(t(), q, family, option, setting, code, limit);
    }
    @GetMapping("/catalogue/models/{id}") public ObjectNode model(@PathVariable String id) { return catalogue.model(t(), id); }
    @PutMapping("/catalogue/models/{id}/posture")
    public ObjectNode posture(@PathVariable String id, @RequestBody JsonNode b) { Current.require(Role.SECURITY_ANALYST, Role.CISO_DPO); return catalogue.recordPosture(t(), id, b, a()); }
    @GetMapping("/catalogue/acceptance") public ObjectNode acceptance() { return catalogue.acceptance(t()); }
    @PutMapping("/catalogue/acceptance") public ObjectNode setAcceptance(@RequestBody JsonNode b) { Current.require(Role.TENANT_ADMIN, Role.DEVICE_ADMIN); return catalogue.setAcceptance(t(), b, a()); }
    @PostMapping("/catalogue/requests") public ObjectNode request(@RequestBody JsonNode b) { return catalogue.requestModel(t(), b, a()); }
    @GetMapping("/catalogue/requests") public List<ObjectNode> requests() { return catalogue.requests(t()); }
    @GetMapping("/catalogue/recalls") public List<ObjectNode> recalls() { return catalogue.recalls(t()); }
    @PostMapping("/catalogue/models/{id}/recalls")
    public ObjectNode addRecall(@PathVariable String id, @RequestBody JsonNode b) { Current.require(Role.DEVICE_ADMIN, Role.REGULATORY); return catalogue.addRecall(t(), id, b, "manual", a()); }
    @PostMapping("/catalogue/recalls/{id}/ack") public ObjectNode ack(@PathVariable String id, @RequestBody(required = false) JsonNode b) { return catalogue.acknowledgeRecall(t(), id, b == null ? null : b.path("note").asString(null), a()); }
    @GetMapping("/catalogue/coverage") public Map<String, Object> coverage() { return catalogue.coverage(t()); }
    @PostMapping("/catalogue/feeds/openfda/{code}") public Map<String, Object> refresh(@PathVariable String code) { Current.require(Role.DEVICE_ADMIN, Role.REGULATORY); return feeds.refresh(t(), code, a()); }
    @GetMapping("/catalogue/feeds/changes") public Map<String, Object> changes() { return Map.of("enabled", feeds.enabled(), "changes", feeds.changes(t())); }
    @PostMapping("/catalogue/feeds/changes/{id}") public ObjectNode review(@PathVariable String id, @RequestBody JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.REGULATORY);
        return feeds.review(t(), id, b.path("approve").asBoolean(false), a());
    }

    // ---------------------------------------------------------------- drivers & option packs
    @GetMapping("/drivers")
    public List<Map<String, Object>> drivers() {
        return drivers.all(t()).stream().map(d -> Map.<String, Object>of("ref", d.ref(), "name", d.name(), "family", d.family(), "options", d.options(), "models", d.models(),
                "channel", d.channel(), "signature", d.signatureStatus(), "publisher", d.publisher(), "templates", List.copyOf(d.bindings().propertyNames()))).toList();
    }
    @GetMapping("/drivers/{ref}") public JsonNode driver(@PathVariable String ref) { return drivers.require(t(), ref).json(); }
    @GetMapping("/drivers-refused") public List<Map<String, Object>> refused() { return drivers.rejected(); }
    @PostMapping("/drivers/validate") public Map<String, Object> validate(@RequestBody JsonNode pkg) { return Map.of("problems", DriverValidator.validate(pkg)); }
    @PostMapping("/drivers/publish")
    public Map<String, Object> publishDriver(@RequestBody JsonNode b) {
        Current.require(Role.INTEGRATION_DEV, Role.DEVICE_ADMIN);
        DriverPackage d = drivers.publish(t(), b.get("package"), b.path("signature").asString(""), a());
        return Map.of("ref", d.ref(), "channel", d.channel(), "publisher", d.publisher());
    }
    @PostMapping("/drivers/publishers")
    public Map<String, String> trust(@RequestBody JsonNode b) {
        Current.require(Role.SECURITY_ANALYST, Role.TENANT_ADMIN);
        drivers.trustPublisher(t(), b.path("name").asString(), b.path("publicKey").asString(), a());
        return Map.of("status", "trusted");
    }
    @GetMapping("/option-packs") public List<ObjectNode> packs() { return packs.all(); }

    // ---------------------------------------------------------------- configurations (wizard)
    @GetMapping("/console/configs") public List<ObjectNode> configs() { return configs.list(t()); }
    @PostMapping("/console/configs")
    public ObjectNode create(@RequestBody JsonNode b) { return configs.create(t(), b.path("name").asString(null), b.path("modelId").asString(), b.get("site"), a()); }
    @GetMapping("/console/configs/{id}") public ObjectNode config(@PathVariable String id) { return configs.get(t(), id); }
    @PutMapping("/console/configs/{id}/steps/{step}")
    public ObjectNode step(@PathVariable String id, @PathVariable int step, @RequestBody JsonNode b) { return configs.saveStep(t(), id, step, b, a()); }
    @PostMapping("/console/configs/{id}/validate") public ArrayNode validateConfig(@PathVariable String id, @RequestBody(required = false) JsonNode probe) {
        ObjectNode c = configs.get(t(), id);
        if (probe != null && probe.isObject()) c.setAll((ObjectNode) probe);
        return configs.validate(t(), c);
    }
    @GetMapping("/console/configs/{id}/recommendations")
    public ArrayNode recs(@PathVariable String id, @RequestParam int step) { Current.require(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.AUDITOR); return configs.recommendations(t(), id, step); }
    @GetMapping("/console/configs/{id}/recommendation-trail") public List<ObjectNode> trail(@PathVariable String id) { return store.listByRef(t(), "recommendation", id); }
    @PostMapping("/console/recommendations/{recId}/decision")
    public ObjectNode decide(@PathVariable String recId, @RequestBody JsonNode b) {
        return configs.decide(t(), recId, b.path("decision").asString(), b.get("value"), b.path("reason").asString(null), a());
    }
    @PostMapping("/console/configs/{id}/accept-all") public ArrayNode acceptAll(@PathVariable String id, @RequestParam int step) { return configs.acceptAll(t(), id, step, a()); }
    @GetMapping("/console/configs/{id}/plan") public ObjectNode plan(@PathVariable String id) { return harness.plan(t(), id); }
    @PostMapping("/console/configs/{id}/test")
    public ObjectNode test(@PathVariable String id) { Current.require(Role.DEVICE_ADMIN, Role.INTEGRATION_DEV, Role.TEMPLATE_OWNER); return harness.run(t(), id, a()); }
    @GetMapping("/console/runs") public List<ObjectNode> runs(@RequestParam(required = false) String configId) { return harness.runs(t(), configId); }
    @GetMapping("/console/runs/{runId}") public ObjectNode run(@PathVariable String runId) { return store.require(t(), "run", runId); }
    @GetMapping("/console/runs/{runId}/evidence")
    public ResponseEntity<byte[]> evidence(@PathVariable String runId) {
        store.require(t(), "run", runId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + runId + "-evidence.zip\"").contentType(MediaType.parseMediaType("application/zip")).body(sealer.read(runId));
    }
    @GetMapping("/console/runs/{runId}/verify") public Map<String, Object> verify(@PathVariable String runId) { store.require(t(), "run", runId); return sealer.verify(sealer.read(runId)); }
    @GetMapping("/console/runs/{runId}/instances") public List<ObjectNode> sandbox(@PathVariable String runId) {
        Current.require(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.INTEGRATION_DEV, Role.AUDITOR);
        return store.listByRef(t(), "sandbox-instance", runId);
    }
    @PostMapping("/console/configs/{id}/submit") public ObjectNode submit(@PathVariable String id) { return configs.submitForCertification(t(), id, a()); }
    @PostMapping("/console/configs/{id}/approve") public ObjectNode approve(@PathVariable String id, @RequestBody(required = false) JsonNode b) { return configs.approve(t(), id, b == null ? "" : b.path("comment").asString(""), a()); }
    @PostMapping("/console/configs/{id}/reject") public ObjectNode reject(@PathVariable String id, @RequestBody(required = false) JsonNode b) { return configs.reject(t(), id, b == null ? "" : b.path("reason").asString(""), a()); }
    @PostMapping("/console/configs/{id}/posture-exception") public ObjectNode postureExc(@PathVariable String id, @RequestBody JsonNode b) { return configs.requestPostureException(t(), id, b.path("justification").asString(), a()); }
    @PostMapping("/console/configs/{id}/publish") public ObjectNode publish(@PathVariable String id, @RequestParam(defaultValue = "DEV") String stage) { return configs.publish(t(), id, stage, a()); }
    @PostMapping("/console/configs/{id}/promotion-approval") public ObjectNode promotionApproval(@PathVariable String id, @RequestParam String stage) { return configs.approvePromotion(t(), id, stage, a()); }
    @PostMapping("/console/configs/{id}/gate-exception") public ObjectNode gateExc(@PathVariable String id, @RequestBody JsonNode b) { return configs.requestGateException(t(), id, b.path("reason").asString(), b.path("days").asInt(7), a()); }
    @PostMapping("/console/configs/{id}/gate-exception/approve") public ObjectNode gateExcApprove(@PathVariable String id) { return configs.approveGateException(t(), id, a()); }
    @PostMapping("/console/configs/{id}/new-version") public ObjectNode newVersion(@PathVariable String id) { return configs.newVersion(t(), id, a()); }
    @PostMapping("/console/configs/{id}/clone") public ObjectNode cloneCfg(@PathVariable String id, @RequestBody JsonNode b) { return configs.clone(t(), id, b.path("name").asString("Clone"), b.get("site"), a()); }
    @PostMapping("/console/configs/{id}/decommission") public ObjectNode decommission(@PathVariable String id, @RequestParam(defaultValue = "true") boolean revokeEndpoint) { return configs.decommission(t(), id, revokeEndpoint, a()); }
    @PostMapping("/console/configs/{id}/calibration") public ObjectNode calibration(@PathVariable String id, @RequestBody JsonNode b) { return configs.calibrationCheck(t(), id, b.path("passed").asBoolean(), b.path("note").asString(null), a()); }
    @PostMapping("/console/configs/{id}/calibration/release") public ObjectNode release(@PathVariable String id, @RequestBody JsonNode b) { return configs.releaseCalibration(t(), id, b.path("reason").asString(), a()); }
    @PostMapping("/console/configs/{id}/qc") public ObjectNode qc(@PathVariable String id, @RequestBody JsonNode b) { return configs.recordQc(t(), id, b.path("lot").asString(), b.path("passed").asBoolean(), a()); }
    @GetMapping("/console/configs/{id}/export")
    public ObjectNode export(@PathVariable String id) {
        ObjectNode c = configs.get(t(), id).deepCopy();
        c.remove("history");
        audit.record(t(), a(), "config.export", "config/" + id, null);
        return c;
    }
    @GetMapping("/console/configs/{id}/audit") public List<Map<String, Object>> configAudit(@PathVariable String id) { return audit.byTarget(t(), "config/" + id); }
    @GetMapping("/console/certifications") public List<ObjectNode> certs() { return store.list(t(), "certification"); }
    @GetMapping("/console/certifications/{id}") public ObjectNode cert(@PathVariable String id) { return store.require(t(), "certification", id); }
    @GetMapping("/console/fleet") public List<ObjectNode> fleet() { return configs.fleet(t()); }

    // ---------------------------------------------------------------- diagnostics (IM-E03-BL-010) – PHI masked
    @GetMapping("/console/configs/{id}/diagnostics")
    public ObjectNode diagnostics(@PathVariable String id) {
        Current.require(Role.DEVICE_ADMIN, Role.INTEGRATION_DEV, Role.SECURITY_ANALYST);
        ArrayNode raws = Json.arr();
        List<ObjectNode> rs = store.listByRef(t(), "raw", id);
        for (ObjectNode r : rs.subList(Math.max(0, rs.size() - 15), rs.size()).reversed()) {
            raws.addObject().put("sha256", r.get("sha256").asString()).put("receivedAt", r.path("receivedAt").asString()).put("mode", r.path("mode").asString())
                    .put("payloadMasked", PhiMasker.mask(r.get("payload").asString()));
        }
        ArrayNode offsets = Json.arr();
        for (ObjectNode o : store.listByRef(t(), "observation", id)) if (o.has("clockOffsetMs")) offsets.add(Json.obj().put("observation", o.get("id").asString()).put("offsetMs", o.get("clockOffsetMs").asLong()));
        ObjectNode out = Json.obj();
        out.set("payloads", raws);
        out.set("clockOffsets", offsets);
        out.set("deadLetters", Json.tree(store.listByRef(t(), "dlq", id)));
        return out;
    }
    @PostMapping("/console/raw/{sha}/resend")
    public Map<String, Object> resend(@PathVariable String sha) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode r = store.require(t(), "raw", sha);
        audit.record(t(), a(), "diagnostics.resend", "raw/" + sha, null);
        return pipeline.process(new Envelope(t(), null, r.get("configId").asString(), r.path("option").asString(), r.get("payload").asString(), r.path("mode").asString("LIVE"),
                null, null, "resend", Instant.now())).summary();
    }

    // ---------------------------------------------------------------- intelligence extras
    @PostMapping("/console/docs") public ObjectNode upload(@RequestBody JsonNode b) { Current.require(Role.DEVICE_ADMIN, Role.INTEGRATION_DEV); return docs.upload(t(), b.path("name").asString("spec"), b.path("text").asString(), a()); }
    @GetMapping("/console/docs") public List<ObjectNode> documents() { return docs.documents(t()); }
    @GetMapping("/console/docs/ask") public List<ObjectNode> ask(@RequestParam String q) { return docs.ask(t(), q, 5); }
    @GetMapping("/console/knowledge-base") public List<ObjectNode> kbExport() { Current.require(Role.DEVICE_ADMIN, Role.CISO_DPO, Role.SECURITY_ANALYST, Role.AUDITOR); return kb.export(); }
    @DeleteMapping("/console/docs/{id}") public Map<String, String> deleteDoc(@PathVariable String id) { store.delete(t(), "spec-doc", id); return Map.of("deleted", id); }
}
