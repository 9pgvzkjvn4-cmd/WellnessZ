package io.intimed.web;

import io.intimed.capture.CaptureService;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.Router;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Templates (IM-E05-F01..F03), capture component back end (IM-E05-F04..F07), and the clinical queues (unmatched,
 * dead letters) and association context (ADT bed binding, worklists, RPM enrolment/consent). Clinical content is only
 * served to clinical roles; device administrators, SOC and SRE never see measurements (architecture §2.2).
 */
@RestController
@RequestMapping("/api")
public class ClinicalController {
    private final TemplateRegistry templates;
    private final CaptureService capture;
    private final DocumentStore store;
    private final Pipeline pipeline;
    private final AssociationEngine association;
    private final Router router;

    public ClinicalController(TemplateRegistry templates, CaptureService capture, DocumentStore store, Pipeline pipeline, AssociationEngine association, Router router) {
        this.templates = templates;
        this.capture = capture;
        this.store = store;
        this.pipeline = pipeline;
        this.association = association;
        this.router = router;
    }

    private static String t() { return Current.tenant(); }
    private static void clinical() { Current.require(Role.CLINICIAN, Role.PHYSICIAN, Role.TEMPLATE_OWNER); }

    // ---------------------------------------------------------------- templates
    @GetMapping("/templates") public List<ObjectNode> list() { return templates.all(t()); }
    @GetMapping("/templates/{id}") public ObjectNode get(@PathVariable String id) { return templates.require(t(), id); }
    @PostMapping("/templates/import/questionnaire")
    public ObjectNode importQ(@RequestBody JsonNode q) { Current.require(Role.TEMPLATE_OWNER); return templates.importQuestionnaire(t(), q, Current.actor()); }
    @GetMapping("/templates/{id}/bindings")
    public ArrayNode bindings(@PathVariable String id) {
        ArrayNode out = Json.arr();
        for (ObjectNode c : store.list(t(), "config")) for (JsonNode b : c.path("bindings")) if (id.equals(b.path("templateId").asString()))
            out.addObject().put("configId", c.get("id").asString()).put("name", c.get("name").asString()).put("status", c.get("status").asString()).put("driver", c.path("driver").asString()).set("fields", b.get("fields"));
        return out;
    }
    @GetMapping("/templates/{id}/instances")
    public List<ObjectNode> instances(@PathVariable String id, @RequestParam(required = false) String subject) { clinical(); return capture.instances(t(), id, subject); }
    @GetMapping("/instances/{kind}/{instanceId}/render/{adapter}")
    public ObjectNode render(@PathVariable String kind, @PathVariable String instanceId, @PathVariable String adapter) {
        if (!List.of("template-instance", "sandbox-instance", "shadow").contains(kind)) throw ApiException.bad("kind", "invalid kind");
        if ("template-instance".equals(kind)) clinical(); else Current.require(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.INTEGRATION_DEV, Role.CLINICIAN, Role.AUDITOR);
        return router.render(t(), adapter, store.require(t(), kind, instanceId));
    }

    // ---------------------------------------------------------------- capture component
    @GetMapping("/capture/devices") public ArrayNode devices(@RequestParam String templateId, @RequestParam(required = false) String station) { return capture.devices(t(), templateId, station); }
    @GetMapping("/capture/fallback") public ArrayNode fallback(@RequestParam String templateId, @RequestParam(required = false) String station) { return capture.fallback(t(), templateId, station); }
    @PostMapping("/capture/sessions")
    public ObjectNode session(@RequestBody JsonNode b) {
        clinical();
        return capture.startSession(t(), b.path("station").asString(null), b.path("subject").asString(null), b.path("templateId").asString(), Current.principal().username());
    }
    @PostMapping("/capture/sessions/{id}/close") public Map<String, String> close(@PathVariable String id) { clinical(); association.closeSession(t(), id); return Map.of("closed", id); }
    @PostMapping("/capture/acquire")
    public ObjectNode acquire(@RequestBody JsonNode b) { clinical(); return capture.acquire(t(), b.path("configId").asString(), b.path("subjectInMessage").asString(null)); }
    @PostMapping("/capture/form/{configId}") public ObjectNode form(@PathVariable String configId, @RequestBody JsonNode b) { clinical(); return capture.submitForm(t(), configId, b, Current.principal().username()); }
    @GetMapping("/capture/pending") public ArrayNode pending(@RequestParam(required = false) String station) { clinical(); return capture.pending(t(), station); }
    @PostMapping("/capture/observations/{id}/accept")
    public ObjectNode accept(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        clinical();
        Map<String, BigDecimal> corr = new HashMap<>();
        if (b != null) b.path("corrections").properties().forEach(e -> corr.put(e.getKey(), new BigDecimal(e.getValue().asString())));
        return capture.accept(t(), id, corr, Current.principal().username());
    }
    @PostMapping("/capture/observations/{id}/reject")
    public ObjectNode reject(@PathVariable String id, @RequestBody(required = false) JsonNode b) { clinical(); return capture.reject(t(), id, b == null ? null : b.path("reason").asString(null), Current.principal().username()); }
    @GetMapping("/capture/observations/{id}") public ObjectNode observation(@PathVariable String id) { clinical(); return store.require(t(), "observation", id); }
    @GetMapping("/capture/recent")
    public List<ObjectNode> recent(@RequestParam(defaultValue = "30") int n) {
        clinical();
        List<ObjectNode> all = store.list(t(), "observation").stream().filter(o -> !"TEST".equals(o.path("mode").asString())).toList();
        return all.subList(Math.max(0, all.size() - n), all.size()).reversed();
    }

    // ---------------------------------------------------------------- queues
    @GetMapping("/ops/unmatched") public List<ObjectNode> unmatched() { clinical(); return store.listByStatus(t(), "unmatched", "OPEN").stream().filter(u -> !"TEST".equals(u.path("mode").asString())).toList(); }
    @PostMapping("/ops/unmatched/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable String id, @RequestBody JsonNode b) { clinical(); return pipeline.resolveUnmatched(t(), id, b.path("subject").asString(), Current.actor()).summary(); }
    @GetMapping("/ops/dlq")
    public List<ObjectNode> dlq() { Current.require(Role.DEVICE_ADMIN, Role.INTEGRATION_DEV, Role.SECURITY_ANALYST); return store.listByStatus(t(), "dlq", "OPEN").stream().filter(d -> !"TEST".equals(d.path("mode").asString())).toList(); }
    @PostMapping("/ops/dlq/{id}/replay") public Map<String, Object> replay(@PathVariable String id) { Current.require(Role.DEVICE_ADMIN, Role.INTEGRATION_DEV); return pipeline.replay(t(), id, Current.actor()).summary(); }
    @PostMapping("/ops/dlq/{id}/discard")
    public ObjectNode discard(@PathVariable String id) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode d = store.require(t(), "dlq", id);
        d.put("status", "DISCARDED").put("discardedBy", Current.actor());
        return store.put(t(), "dlq", id, d, "DISCARDED", d.path("configId").asString());
    }

    // ---------------------------------------------------------------- association context
    @GetMapping("/context/subjects") public List<ObjectNode> subjects() { clinical(); return store.list(t(), "subject"); }
    @PostMapping("/context/subjects")
    public ObjectNode subject(@RequestBody JsonNode b) {
        clinical();
        String id = b.path("mrn").asString(Hashing.id("sub"));
        ObjectNode s = Json.obj().put("id", id).put("mrn", id).put("name", b.path("name").asString("")).put("badge", b.path("badge").asString(""));
        s.set("vendorIds", b.path("vendorIds").isArray() ? b.get("vendorIds") : Json.arr());
        // demographics for reference-interval partitioning (IM-E14) – validated, never free text
        if (b.hasNonNull("birthDate")) s.put("birthDate", java.time.LocalDate.parse(b.get("birthDate").asString()).toString());
        String sex = b.path("sex").asString("");
        if (java.util.List.of("male", "female").contains(sex)) s.put("sex", sex);
        if (b.path("pregnant").asBoolean(false)) s.put("pregnant", true).put("trimester", Math.max(1, Math.min(3, b.path("trimester").asInt(2))));
        return store.put(t(), "subject", id, s, "ACTIVE", null);
    }
    @GetMapping("/context/beds") public List<ObjectNode> beds() { clinical(); return store.list(t(), "bed"); }
    @PostMapping("/context/adt")
    public Map<String, String> adt(@RequestBody JsonNode b) {
        clinical();
        switch (b.path("event").asString()) {
            case "A01" -> association.admit(t(), b.path("bed").asString(), b.path("subject").asString(), b.path("encounter").asString("ENC-" + Hashing.randomToken(4)));
            case "A02" -> association.transfer(t(), b.path("fromBed").asString(), b.path("bed").asString());
            case "A03" -> association.discharge(t(), b.path("bed").asString());
            default -> throw ApiException.bad("event", "event must be A01, A02 or A03");
        }
        return Map.of("status", "applied", "event", b.path("event").asString());
    }
    @PostMapping("/context/worklist")
    public ObjectNode worklist(@RequestBody JsonNode b) {
        clinical();
        return association.openWorklist(t(), b.path("station").asString(), b.path("subject").asString(), b.path("templateId").asString(), b.path("orderRef").asString("ORD-" + Hashing.randomToken(4)), 240);
    }
    @GetMapping("/context/worklist") public List<ObjectNode> worklists() { clinical(); return store.listByStatus(t(), "worklist", "OPEN"); }
    @GetMapping("/context/enrolments") public List<ObjectNode> enrolments() { clinical(); return store.list(t(), "enrolment"); }
    @PostMapping("/context/enrolments")
    public ObjectNode enrol(@RequestBody JsonNode b) {
        clinical();
        List<String> scopes = new java.util.ArrayList<>();
        b.path("scopes").forEach(s -> scopes.add(s.asString()));
        return association.enrol(t(), b.path("deviceSerial").asString(), b.path("subject").asString(), b.path("configId").asString(), scopes, b.path("days").asInt(90));
    }
    @PostMapping("/context/enrolments/{id}/withdraw")
    public Map<String, String> withdraw(@PathVariable String id) { Current.require(Role.CLINICIAN, Role.PATIENT, Role.PHYSICIAN); association.withdrawConsent(t(), id); return Map.of("consent", "withdrawn", "at", Instant.now().toString()); }
}
