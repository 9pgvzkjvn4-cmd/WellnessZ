package io.intimed.console;

import io.intimed.catalogue.CatalogueService;
import io.intimed.common.ApiException;
import io.intimed.common.Env;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.intelligence.KnowledgeBase;
import io.intimed.console.intelligence.PolicyEngine;
import io.intimed.console.intelligence.RecommendationEngine;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Guided provisioning console (IM-E03): device configurations as governed drafts moving through the eight wizard steps
 * (select device → option → prerequisites → parameters → association & bindings → exchange test → certify → publish),
 * with recommendations and decisions, pre-save validation, change history, four-eyes certification, publish gate
 * (SC-20) and environment promotion DEV → UAT → PROD with diffs, calibration/QC lockout, decommissioning and cloning.
 * Published configurations are immutable: changes are made on a new version that supersedes the old one.
 */
@Service
public class ConfigService {
    public static final String KIND = "config";
    private static final Set<String> TECHNICAL = Set.of("driver", "option", "params", "association", "bindings", "qualityGates", "site", "intervalMinutes", "captureMode", "expectedSerials");
    private final DocumentStore store;
    private final DriverRegistry drivers;
    private final CatalogueService catalogue;
    private final RecommendationEngine intelligence;
    private final PolicyEngine policy;
    private final KnowledgeBase kb;
    private final TenantService tenants;
    private final AuditService audit;
    private final EventBus events;
    private final EndpointService endpoints;

    public ConfigService(DocumentStore store, DriverRegistry drivers, CatalogueService catalogue, RecommendationEngine intelligence, PolicyEngine policy,
                         KnowledgeBase kb, TenantService tenants, AuditService audit, EventBus events, EndpointService endpoints) {
        this.store = store;
        this.drivers = drivers;
        this.catalogue = catalogue;
        this.intelligence = intelligence;
        this.policy = policy;
        this.kb = kb;
        this.tenants = tenants;
        this.audit = audit;
        this.events = events;
        this.endpoints = endpoints;
    }

    // ---------------------------------------------------------------- lifecycle
    public ObjectNode create(String tenant, String name, String modelId, JsonNode site, String actor) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode m = catalogue.requireModel(modelId);
        if (!CatalogueService.accepted(catalogue.acceptance(tenant), m)) throw ApiException.forbidden("model-not-accepted", "model is not accepted by the tenant policy");
        String id = "cfg-" + Hashing.randomToken(6).toLowerCase().replaceAll("[^a-z0-9]", "0");
        ObjectNode c = Json.obj().put("id", id).put("name", name == null || name.isBlank() ? m.get("model").asString() : name).put("modelId", modelId)
                .put("family", m.get("family").asString()).put("manufacturer", m.get("manufacturer").asString()).put("model", m.get("model").asString())
                .put("status", "DRAFT").put("stage", "DEV").put("version", 1).put("createdAt", Instant.now().toString()).put("createdBy", actor);
        c.set("site", site == null || site.isNull() ? Json.obj().put("site", "Main clinic").put("station", "ST-01") : site);
        c.putObject("params");
        c.putObject("association");
        c.putArray("bindings");
        c.putObject("qualityGates");
        c.putObject("steps").put("1", "IN_PROGRESS");
        c.putArray("editors").add(actor);
        c.putArray("history").addObject().put("at", Instant.now().toString()).put("by", actor).put("action", "created");
        c.putObject("justifications");
        store.put(tenant, KIND, id, c, "DRAFT", modelId);
        audit.record(tenant, actor, "config.create", "config/" + id, Map.of("model", modelId));
        return c;
    }

    public ObjectNode get(String tenant, String id) { return store.require(tenant, KIND, id); }

    public List<ObjectNode> list(String tenant) { return store.list(tenant, KIND); }

    public ObjectNode save(String tenant, ObjectNode c, String action, String actor, Object detail) {
        c.put("hash", hash(c));
        c.withArray("history").addObject().put("at", Instant.now().toString()).put("by", actor).put("action", action).put("hash", c.get("hash").asString().substring(0, 12));
        store.put(tenant, KIND, c.get("id").asString(), c, c.get("status").asString(), c.get("modelId").asString());
        audit.record(tenant, actor, "config." + action, "config/" + c.get("id").asString(), detail);
        return c;
    }

    public static String hash(JsonNode c) {
        ObjectNode t = Json.obj();
        TECHNICAL.forEach(k -> { if (c.has(k)) t.set(k, c.get(k)); });
        return Hashing.sha256(Json.canonical(t));
    }

    private void requireEditable(ObjectNode c) {
        String s = c.get("status").asString();
        if (List.of("PUBLISHED", "SUPERSEDED", "DECOMMISSIONED", "PENDING_APPROVAL").contains(s)) {
            throw ApiException.conflict("not-editable", "configuration is " + s + " – create a new version to change it");
        }
    }

    /** Saves one wizard step after pre-save validation; any technical change after a test invalidates it (re-test trigger). */
    public ObjectNode saveStep(String tenant, String id, int step, JsonNode data, String actor) {
        Current.require(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER);
        ObjectNode c = get(tenant, id);
        requireEditable(c);
        if (step == 5 && Current.principal().has(Role.TEMPLATE_OWNER) && !Current.principal().has(Role.DEVICE_ADMIN)) {
            // template owners own bindings only (no device transport settings – architecture §2.2)
            data.propertyNames().forEach(k -> { if (!List.of("bindings").contains(k)) throw ApiException.forbidden("role-boundary", "template owners may change bindings only"); });
        }
        String before = c.path("hash").asString(hash(c));
        data.properties().forEach(e -> {
            switch (e.getKey()) {
                case "driver", "option", "endpointId", "name", "captureMode", "intervalMinutes", "shadowDays", "certificationSummary" -> c.set(e.getKey(), e.getValue());
                case "site", "association", "qualityGates", "calibration", "monitoring", "harness", "prerequisites" -> {
                    ObjectNode cur = c.has(e.getKey()) && c.get(e.getKey()).isObject() ? (ObjectNode) c.get(e.getKey()) : c.putObject(e.getKey());
                    cur.setAll((ObjectNode) e.getValue());
                }
                case "params" -> ((ObjectNode) c.get("params")).setAll((ObjectNode) e.getValue());
                case "bindings", "expectedSerials" -> c.set(e.getKey(), e.getValue());
                case "justifications" -> ((ObjectNode) c.get("justifications")).setAll((ObjectNode) e.getValue());
                default -> throw ApiException.bad("unknown-field", "field " + e.getKey() + " cannot be set in step " + step);
            }
        });
        if (data.has("driver")) {
            DriverPackage d = drivers.require(tenant, data.get("driver").asString());
            if (!d.models().contains(c.get("modelId").asString())) throw ApiException.bad("driver-model", "driver does not support model " + c.get("modelId").asString());
            c.put("captureMode", d.manifest().path("captureMode").asString("SPOT"));
            c.put("restricted", d.restricted());
        }
        boolean editorAdded = false;
        for (JsonNode e : c.withArray("editors")) editorAdded |= e.asString().equals(actor);
        if (!editorAdded) c.withArray("editors").add(actor);
        c.withObject("steps").put(String.valueOf(step), "DONE");
        if (step < 8) c.withObject("steps").put(String.valueOf(step + 1), c.withObject("steps").path(String.valueOf(step + 1)).asString("IN_PROGRESS"));
        String after = hash(c);
        if (!after.equals(before) && List.of("TESTED", "CERTIFIED").contains(c.get("status").asString())) {
            c.put("status", "DRAFT").put("retestReason", "technical change after test (step " + step + ")");
            ((ObjectNode) c.get("steps")).put("6", "RETEST_REQUIRED");
        }
        ArrayNode violations = validate(tenant, c);
        c.set("violations", violations);
        if (PolicyEngine.blocking(violations, c.get("justifications"))) {
            c.put("blocked", true);
        } else {
            c.put("blocked", false);
        }
        return save(tenant, c, "step" + step, actor, Map.of("step", step, "changed", data.propertyNames()));
    }

    public ArrayNode validate(String tenant, JsonNode c) {
        DriverPackage d = c.hasNonNull("driver") ? drivers.find(tenant, c.get("driver").asString()).orElse(null) : null;
        return policy.validate(tenant, c, d, tenants.require(tenant).settings());
    }

    // ---------------------------------------------------------------- recommendations & decisions (IM-E03-BL-004)
    public ArrayNode recommendations(String tenant, String id, int step) {
        ObjectNode c = get(tenant, id);
        ArrayNode recs = intelligence.recommend(tenant, c, tenants.require(tenant).settings(), step);
        for (JsonNode r : recs) {
            ObjectNode rr = (ObjectNode) r;
            rr.put("configId", id);
            // policy outcome of the recommended value itself
            if (rr.get("parameter").asString().startsWith("params.") && rr.get("policy").asString().equals("ALLOWED")) {
                ObjectNode probe = (ObjectNode) c.deepCopy();
                ((ObjectNode) probe.get("params")).set(rr.get("parameter").asString().substring(7), rr.get("value"));
                if (PolicyEngine.blocking(validateOnly(tenant, probe, rr.get("parameter").asString()), null)) rr.put("policy", "WARN");
            }
            store.put(tenant, "recommendation", rr.get("id").asString(), rr, "PROPOSED", id);
        }
        return recs;
    }

    private ArrayNode validateOnly(String tenant, JsonNode c, String param) {
        ArrayNode all = validate(tenant, c);
        ArrayNode out = Json.arr();
        for (JsonNode v : all) if (v.get("parameter").asString().equals(param)) out.add(v);
        return out;
    }

    /** ACCEPT (apply value), ADJUST (apply the administrator's value, reason required), OVERRIDE (keep current, reason required). */
    public ObjectNode decide(String tenant, String recId, String decision, JsonNode value, String reason, String actor) {
        Current.require(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER);
        ObjectNode r = store.require(tenant, "recommendation", recId);
        if (!"PROPOSED".equals(r.get("status").asString())) throw ApiException.conflict("decided", "recommendation already decided");
        if (!List.of("ACCEPT", "ADJUST", "OVERRIDE").contains(decision)) throw ApiException.bad("invalid-decision", "decision must be ACCEPT, ADJUST or OVERRIDE");
        if (!"ACCEPT".equals(decision) && (reason == null || reason.isBlank())) throw ApiException.bad("reason-required", "a reason is required to adjust or override a recommendation");
        if ("BLOCKED".equals(r.get("policy").asString()) && "ACCEPT".equals(decision)) throw ApiException.conflict("blocked", "recommendation is blocked by policy");
        ObjectNode c = get(tenant, r.get("configId").asString());
        requireEditable(c);
        JsonNode applied = "ACCEPT".equals(decision) ? r.get("value") : "ADJUST".equals(decision) ? value : null;
        if (applied != null && !applied.isNull()) apply(c, r.get("parameter").asString(), applied, tenant);
        r.put("status", decision.equals("ACCEPT") ? "ACCEPTED" : decision.equals("ADJUST") ? "ADJUSTED" : "OVERRIDDEN").put("decidedBy", actor)
                .put("decidedAt", Instant.now().toString()).put("reason", reason);
        if (applied != null) r.set("appliedValue", applied);
        store.put(tenant, "recommendation", recId, r, r.get("status").asString(), c.get("id").asString());
        c.withArray("decisions").addObject().put("rec", recId).put("parameter", r.get("parameter").asString()).put("rule", r.get("rule").asString())
                .put("source", r.get("source").asString()).put("confidence", r.get("confidence").asString()).put("decision", r.get("status").asString())
                .put("by", actor).put("reason", reason);
        boolean already = false;
        for (JsonNode e : c.withArray("editors")) already |= e.asString().equals(actor);
        if (!already && applied != null) c.withArray("editors").add(actor);
        if (applied != null && List.of("TESTED", "CERTIFIED").contains(c.get("status").asString()) && !hash(c).equals(c.path("hash").asString())) {
            c.put("status", "DRAFT").put("retestReason", "recommendation applied after test");
        }
        c.set("violations", validate(tenant, c));
        c.put("blocked", PolicyEngine.blocking((ArrayNode) c.get("violations"), c.get("justifications")));
        save(tenant, c, "decision", actor, Map.of("rule", r.get("rule").asString(), "parameter", r.get("parameter").asString(), "decision", r.get("status").asString(),
                "source", r.get("source").asString(), "confidence", r.get("confidence").asString()));
        return r;
    }

    /** 'Accept all recommended' – development stages only (SC-19 guardrail, security kit script 18). */
    public ArrayNode acceptAll(String tenant, String id, int step, String actor) {
        ObjectNode c = get(tenant, id);
        Env stage = Env.valueOf(c.path("stage").asString("DEV"));
        if (!stage.allowsAcceptAll()) {
            audit.record(tenant, actor, "config.accept-all.refused", "config/" + id, Map.of("stage", stage.name()));
            throw ApiException.forbidden("guardrail", "'Accept all' is not available for " + stage + " – each recommendation must be decided individually (SC-19)");
        }
        ArrayNode done = Json.arr();
        for (ObjectNode r : store.listByRef(tenant, "recommendation", id)) {
            if (r.get("step").asInt() == step && "PROPOSED".equals(r.get("status").asString()) && List.of("ALLOWED", "WARN").contains(r.get("policy").asString())
                    && !r.get("value").isNull() && !r.get("parameter").asString().startsWith("notes.") && !List.of("prerequisites", "posture", "recalls", "timeSource", "firewall", "cloneTargets", "certificationSummary").contains(r.get("parameter").asString())
                    && !r.get("parameter").asString().startsWith("conversions.")) {
                done.add(decide(tenant, r.get("id").asString(), "ACCEPT", null, null, actor));
            }
        }
        return done;
    }

    void apply(ObjectNode c, String param, JsonNode v, String tenant) {
        switch (param) {
            case "driver" -> {
                DriverPackage d = drivers.require(tenant, v.asString());
                c.put("driver", v.asString()).put("captureMode", d.manifest().path("captureMode").asString("SPOT")).put("restricted", d.restricted());
            }
            case "templates", "prerequisites", "posture", "recalls", "timeSource", "firewall", "cloneTargets" -> c.withObject("accepted").set(param, v);
            case "bindings" -> c.set("bindings", v);
            case "qualityGates" -> c.set("qualityGates", v);
            case "monitoring" -> c.set("monitoring", v);
            default -> {
                if (param.startsWith("notes.") || param.startsWith("conversions.")) { c.withObject("accepted").set(param, v); return; }
                String[] parts = param.split("\\.");
                ObjectNode cur = c;
                for (int i = 0; i < parts.length - 1; i++) cur = cur.has(parts[i]) && cur.get(parts[i]).isObject() ? (ObjectNode) cur.get(parts[i]) : cur.putObject(parts[i]);
                cur.set(parts[parts.length - 1], v);
            }
        }
    }

    // ---------------------------------------------------------------- four-eyes certification (IM-E03-BL-011/013)
    public ObjectNode submitForCertification(String tenant, String id, String actor) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode c = get(tenant, id);
        if (!"TESTED".equals(c.get("status").asString())) throw ApiException.conflict("not-tested", "a passing exchange test is required before certification");
        if (!c.path("hash").asString().equals(c.path("lastPassingRunHash").asString())) throw ApiException.conflict("stale-test", "configuration changed since the last passing test");
        ArrayNode v = validate(tenant, c);
        if (PolicyEngine.blocking(v, c.get("justifications"))) throw ApiException.conflict("policy-blocked", "unresolved BLOCK/JUSTIFY policy findings");
        c.put("status", "PENDING_APPROVAL").put("submittedBy", actor).put("submittedAt", Instant.now().toString());
        return save(tenant, c, "submit", actor, null);
    }

    public ObjectNode approve(String tenant, String id, String comment, String actor) {
        IntiPrincipal p = Current.principal();
        if (!p.hasAny(Role.DEVICE_ADMIN, Role.TEMPLATE_OWNER, Role.SECURITY_ANALYST)) throw ApiException.forbidden("role-required", "approver role required");
        ObjectNode c = get(tenant, id);
        if (!"PENDING_APPROVAL".equals(c.get("status").asString())) throw ApiException.conflict("not-pending", "configuration is not awaiting approval");
        for (JsonNode e : c.withArray("editors")) {
            if (e.asString().equals(actor)) {
                audit.record(tenant, actor, "config.approve.refused", "config/" + id, Map.of("reason", "four-eyes: approver edited the configuration"));
                throw ApiException.forbidden("four-eyes", "four-eyes rule: an editor of this configuration cannot approve it");
            }
        }
        ArrayNode approvals = c.withArray("approvals");
        for (JsonNode a : approvals) if (a.path("by").asString().equals(actor)) throw ApiException.conflict("already-approved", "already approved by this user");
        approvals.addObject().put("by", actor).put("role", p.has(Role.SECURITY_ANALYST) ? "SECURITY" : "CLINICAL_TECHNICAL").put("at", Instant.now().toString()).put("comment", comment);
        boolean needsSecurity = c.path("postureException").path("requested").asBoolean(false) || !"ACCEPTABLE".equals(catalogue.posture(tenant, c.get("modelId").asString()).path("status").asString());
        boolean hasTech = false, hasSec = false;
        for (JsonNode a : approvals) { hasTech |= "CLINICAL_TECHNICAL".equals(a.path("role").asString()); hasSec |= "SECURITY".equals(a.path("role").asString()); }
        if (hasTech && (!needsSecurity || hasSec)) {
            c.put("status", "CERTIFIED").put("certifiedAt", Instant.now().toString());
            if (needsSecurity) c.withObject("postureException").put("approved", true).put("approvedBy", actor);
            ObjectNode cert = certificationRecord(tenant, c);
            c.put("certificationId", cert.get("id").asString());
            kb.contribute(tenants.require(tenant).settings().path("knowledgeBaseOptIn").asBoolean(true), c, "CERTIFIED");
            drivers.certify(tenant, c.get("driver").asString(), c.path("lastRun").path("runId").asString(), actor);
            events.publish(tenant, "device.certified", Json.obj().put("config", id).put("certification", cert.get("id").asString()));
        }
        return save(tenant, c, "approve", actor, Map.of("certified", "CERTIFIED".equals(c.get("status").asString()), "needsSecurity", needsSecurity));
    }

    public ObjectNode reject(String tenant, String id, String reason, String actor) {
        ObjectNode c = get(tenant, id);
        if (!"PENDING_APPROVAL".equals(c.get("status").asString())) throw ApiException.conflict("not-pending", "configuration is not awaiting approval");
        c.put("status", "TESTED").put("rejectedReason", reason);
        return save(tenant, c, "reject", actor, Map.of("reason", reason == null ? "" : reason));
    }

    ObjectNode certificationRecord(String tenant, ObjectNode c) {
        String id = Hashing.id("cert");
        ObjectNode snapshot = (ObjectNode) c.deepCopy();
        snapshot.remove("history");
        ObjectNode rec = Json.obj().put("id", id).put("configId", c.get("id").asString()).put("configHash", c.get("hash").asString())
                .put("driver", c.path("driver").asString()).put("model", c.path("model").asString()).put("runId", c.path("lastRun").path("runId").asString())
                .put("evidenceSeal", c.path("lastRun").path("seal").asString()).put("createdAt", Instant.now().toString())
                .put("summary", c.path("certificationSummary").asString(""));
        rec.set("approvals", c.get("approvals"));
        rec.set("recommendationTrail", c.path("decisions"));
        rec.set("justifications", c.path("justifications"));
        rec.set("postureException", c.path("postureException"));
        rec.set("configuration", snapshot);
        store.put(tenant, "certification", id, rec, "ACTIVE", c.get("id").asString());
        return rec;
    }

    public ObjectNode requestPostureException(String tenant, String id, String justification, String actor) {
        ObjectNode c = get(tenant, id);
        if (justification == null || justification.length() < 10) throw ApiException.bad("justification", "a justification is required");
        c.putObject("postureException").put("requested", true).put("justification", justification).put("requestedBy", actor).put("requestedAt", Instant.now().toString());
        c.withObject("justifications").put("SC-07:posture", justification);
        return save(tenant, c, "posture-exception", actor, null);
    }

    // ---------------------------------------------------------------- publish gate & promotion (SC-20, IM-E04-BL-004)
    public ObjectNode publish(String tenant, String id, String stage, String actor) {
        Current.require(Role.DEVICE_ADMIN, Role.PLATFORM_SRE);
        ObjectNode c = get(tenant, id);
        Env target = Env.valueOf(stage == null ? "DEV" : stage);
        Env current = Env.valueOf(c.path("stage").asString("DEV"));
        boolean published = "PUBLISHED".equals(c.get("status").asString());
        if (published && target.ordinal() != current.ordinal() + 1) throw ApiException.conflict("promotion-order", "promotion goes DEV → UAT → PROD one stage at a time");
        if (target == Env.PROD && Current.principal().has(Role.DEVICE_ADMIN) && !Current.principal().has(Role.PLATFORM_SRE)) {
            boolean sre = false;
            for (JsonNode a : c.withArray("promotionApprovals")) sre |= "PROD".equals(a.path("stage").asString());
            if (!sre) throw ApiException.forbidden("four-eyes", "PROD promotion requires a Platform SRE (four-eyes with the device administrator)");
        }
        boolean gateOk = c.path("hash").asString().equals(c.path("lastPassingRunHash").asString());
        ObjectNode exc = store.get(tenant, "gate-exception", id).orElse(null);
        boolean excOk = exc != null && "APPROVED".equals(exc.path("status").asString()) && Instant.parse(exc.get("expires").asString()).isAfter(Instant.now());
        if (target.requiresPublishGate() || target == Env.DEV) {
            if (!gateOk && !excOk) {
                audit.record(tenant, actor, "publish.gate.blocked", "config/" + id, Map.of("stage", target.name()));
                throw ApiException.conflict("publish-gate", "publish gate: no passing exchange test for the current configuration hash and no approved exception (SC-20)");
            }
            if (!List.of("CERTIFIED", "PUBLISHED").contains(c.get("status").asString())) throw ApiException.conflict("not-certified", "configuration must be certified (four-eyes) before publishing");
        }
        if (c.path("recertificationRequired").asBoolean(false)) throw ApiException.conflict("recertification-required", "endpoint was isolated – re-run the exchange test first");
        ObjectNode prev = c.has("stages") ? (ObjectNode) c.get("stages").get(current.name()) : null;
        ObjectNode snap = Json.obj().put("hash", c.get("hash").asString()).put("at", Instant.now().toString()).put("by", actor).put("gate", gateOk ? "PASSED_RUN" : "EXCEPTION");
        snap.set("diff", diff(prev == null ? null : prev.get("config"), c));
        ObjectNode tech = Json.obj();
        TECHNICAL.forEach(k -> { if (c.has(k)) tech.set(k, c.get(k)); });
        snap.set("config", tech);
        c.withObject("stages").set(target.name(), snap);
        c.put("stage", target.name()).put("status", "PUBLISHED").put("publishedAt", Instant.now().toString());
        if (c.hasNonNull("endpointId")) endpoints.bindConfig(tenant, c.get("endpointId").asString(), id);
        if (c.has("supersedes")) {
            store.get(tenant, KIND, c.get("supersedes").asString()).ifPresent(old -> {
                old.put("status", "SUPERSEDED").put("supersededBy", id);
                store.put(tenant, KIND, old.get("id").asString(), old, "SUPERSEDED", old.get("modelId").asString());
                endpoints.unbindConfig(tenant, old.get("id").asString());
                if (c.hasNonNull("endpointId")) endpoints.bindConfig(tenant, c.get("endpointId").asString(), id);
            });
        }
        if (!c.has("calibration") || !c.get("calibration").has("dueDate")) {
            int days = c.path("calibration").path("intervalDays").asInt(365);
            c.withObject("calibration").put("lastDate", LocalDate.now().toString()).put("dueDate", LocalDate.now().plusDays(days).toString()).put("status", "OK").put("intervalDays", days);
        }
        if (c.path("shadowDays").asInt(0) > 0 && !c.has("shadow")) c.putObject("shadow").put("until", Instant.now().plusSeconds(c.get("shadowDays").asLong() * 86400).toString()).put("signedOff", false);
        tenants.meter(tenant, "devices.published", 1);
        events.publish(tenant, "device.published", Json.obj().put("config", id).put("stage", target.name()));
        return save(tenant, c, "publish", actor, Map.of("stage", target.name(), "gate", gateOk ? "PASSED_RUN" : "EXCEPTION"));
    }

    public ObjectNode approvePromotion(String tenant, String id, String stage, String actor) {
        Current.require(Role.PLATFORM_SRE);
        ObjectNode c = get(tenant, id);
        c.withArray("promotionApprovals").addObject().put("stage", stage).put("by", actor).put("at", Instant.now().toString());
        return save(tenant, c, "promotion-approval", actor, Map.of("stage", stage));
    }

    static ArrayNode diff(JsonNode before, JsonNode after) {
        ArrayNode d = Json.arr();
        for (String k : TECHNICAL) {
            JsonNode a = before == null ? null : before.get(k);
            JsonNode b = after.get(k);
            if (a == null && b == null) continue;
            if (a == null || b == null || !Json.canonical(a).equals(Json.canonical(b))) {
                d.addObject().put("field", k).put("before", a == null ? null : Json.write(a)).put("after", b == null ? null : Json.write(b));
            }
        }
        return d;
    }

    public ObjectNode requestGateException(String tenant, String id, String reason, int days, String actor) {
        if (reason == null || reason.length() < 10) throw ApiException.bad("reason", "a reason is required");
        ObjectNode e = Json.obj().put("configId", id).put("reason", reason).put("requestedBy", actor).put("requestedAt", Instant.now().toString())
                .put("expires", Instant.now().plusSeconds(Math.min(days, 30) * 86400L).toString()).put("status", "REQUESTED");
        store.put(tenant, "gate-exception", id, e, "REQUESTED", id);
        audit.record(tenant, actor, "publish.gate.exception.request", "config/" + id, Map.of("reason", reason));
        return e;
    }

    public ObjectNode approveGateException(String tenant, String id, String actor) {
        Current.require(Role.SECURITY_ANALYST, Role.CISO_DPO);
        ObjectNode e = store.require(tenant, "gate-exception", id);
        if (e.path("requestedBy").asString().equals(actor)) throw ApiException.forbidden("four-eyes", "requester cannot approve the exception");
        e.put("status", "APPROVED").put("approvedBy", actor).put("approvedAt", Instant.now().toString());
        store.put(tenant, "gate-exception", id, e, "APPROVED", id);
        audit.record(tenant, actor, "publish.gate.exception.approve", "config/" + id, null);
        return e;
    }

    /** Clinical sign-off ends the shadow run (template owner / physician) – live filing starts. */
    public ObjectNode signOffShadow(String tenant, String id, String actor) {
        Current.require(Role.TEMPLATE_OWNER, Role.PHYSICIAN);
        ObjectNode c = get(tenant, id);
        if (!c.has("shadow")) throw ApiException.conflict("no-shadow", "configuration is not in shadow mode");
        c.withObject("shadow").put("signedOff", true).put("signedOffBy", actor).put("signedOffAt", Instant.now().toString());
        store.put(tenant, KIND, id, c, c.get("status").asString(), c.get("modelId").asString());
        audit.record(tenant, actor, "config.shadow.signoff", "config/" + id, null);
        return c;
    }

    // ---------------------------------------------------------------- versions, clone, decommission
    public ObjectNode newVersion(String tenant, String id, String actor) {
        ObjectNode old = get(tenant, id);
        ObjectNode c = cloneInto(tenant, old, actor, old.get("name").asString());
        c.put("version", old.path("version").asInt(1) + 1).put("supersedes", id);
        return save(tenant, c, "new-version", actor, Map.of("supersedes", id));
    }

    public ObjectNode clone(String tenant, String id, String name, JsonNode site, String actor) {
        ObjectNode c = cloneInto(tenant, get(tenant, id), actor, name);
        if (site != null && site.isObject()) c.set("site", site);
        return save(tenant, c, "clone", actor, Map.of("from", id));
    }

    private ObjectNode cloneInto(String tenant, ObjectNode src, String actor, String name) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode c = (ObjectNode) src.deepCopy();
        String nid = "cfg-" + Hashing.randomToken(6).toLowerCase().replaceAll("[^a-z0-9]", "0");
        for (String k : List.of("lastRun", "lastPassingRunHash", "lastPassingRunAt", "approvals", "certificationId", "certifiedAt", "stages", "publishedAt",
                "submittedBy", "decisions", "history", "promotionApprovals", "shadow", "supersededBy", "retestReason", "recertificationRequired")) c.remove(k);
        c.put("id", nid).put("name", name).put("status", "DRAFT").put("stage", "DEV").put("createdAt", Instant.now().toString()).put("createdBy", actor).put("clonedFrom", src.get("id").asString());
        c.putArray("editors").add(actor);
        c.putArray("history");
        c.withObject("steps").put("6", "RETEST_REQUIRED");
        return c;
    }

    public ObjectNode decommission(String tenant, String id, boolean revokeEndpoint, String actor) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode c = get(tenant, id);
        if (revokeEndpoint && c.hasNonNull("endpointId")) {
            ObjectNode e = endpoints.require(tenant, c.get("endpointId").asString());
            if (e.withArray("configs").size() <= 1) endpoints.setStatus(tenant, e.get("id").asString(), "REVOKED", "decommissioned with " + id, actor);
        }
        endpoints.unbindConfig(tenant, id);
        ObjectNode archive = (ObjectNode) c.deepCopy();
        store.put(tenant, "config-archive", id, archive, "ARCHIVED", c.get("modelId").asString());
        c.set("archivedBindings", c.get("bindings"));
        c.putArray("bindings");
        c.put("status", "DECOMMISSIONED").put("decommissionedAt", Instant.now().toString());
        return save(tenant, c, "decommission", actor, Map.of("revokedEndpoint", revokeEndpoint));
    }

    // ---------------------------------------------------------------- calibration & QC (IM-E03-BL-008)
    public ObjectNode calibrationCheck(String tenant, String id, boolean passed, String note, String actor) {
        Current.require(Role.CLINICIAN, Role.DEVICE_ADMIN, Role.PHYSICIAN);
        ObjectNode c = get(tenant, id);
        ObjectNode cal = c.withObject("calibration");
        int days = cal.path("intervalDays").asInt(365);
        cal.withArray("history").addObject().put("at", Instant.now().toString()).put("by", actor).put("passed", passed).put("note", note == null ? "" : note);
        if (passed) cal.put("status", "OK").put("lastDate", LocalDate.now().toString()).put("dueDate", LocalDate.now().plusDays(days).toString());
        else cal.put("status", "LOCKED");
        store.put(tenant, KIND, id, c, c.get("status").asString(), c.get("modelId").asString());
        audit.record(tenant, actor, passed ? "calibration.pass" : "calibration.fail", "config/" + id, Map.of("status", cal.get("status").asString()));
        return c;
    }

    public ObjectNode releaseCalibration(String tenant, String id, String reason, String actor) {
        Current.require(Role.DEVICE_ADMIN);
        ObjectNode c = get(tenant, id);
        ObjectNode cal = c.withObject("calibration");
        cal.put("status", "OK").put("dueDate", LocalDate.now().plusDays(cal.path("intervalDays").asInt(365)).toString()).put("releasedBy", actor).put("releaseReason", reason);
        store.put(tenant, KIND, id, c, c.get("status").asString(), c.get("modelId").asString());
        audit.record(tenant, actor, "calibration.release", "config/" + id, Map.of("reason", reason == null ? "" : reason));
        return c;
    }

    public ObjectNode recordQc(String tenant, String id, String lot, boolean passed, String actor) {
        Current.require(Role.CLINICIAN, Role.DEVICE_ADMIN);
        ObjectNode c = get(tenant, id);
        c.withObject("qc").withObject("lots").putObject(lot).put("qcPassed", passed).put("recordedBy", actor).put("at", Instant.now().toString());
        store.put(tenant, KIND, id, c, c.get("status").asString(), c.get("modelId").asString());
        audit.record(tenant, actor, "qc.record", "config/" + id, Map.of("lot", lot, "passed", passed));
        return c;
    }

    // ---------------------------------------------------------------- fleet (IM-E03-BL-009)
    public List<ObjectNode> fleet(String tenant) {
        return list(tenant).stream().filter(c -> List.of("PUBLISHED", "CERTIFIED", "TESTED").contains(c.get("status").asString())).map(c -> {
            ObjectNode f = Json.obj().put("configId", c.get("id").asString()).put("name", c.get("name").asString()).put("model", c.path("model").asString())
                    .put("family", c.path("family").asString()).put("driver", c.path("driver").asString()).put("option", c.path("option").asString())
                    .put("status", c.get("status").asString()).put("stage", c.path("stage").asString())
                    .put("site", c.at("/site/site").asString("")).put("room", c.at("/site/room").asString("")).put("station", c.at("/site/station").asString("")).put("bed", c.at("/site/bed").asString(""))
                    .put("calibration", c.path("calibration").path("status").asString("n/a")).put("calibrationDue", c.path("calibration").path("dueDate").asString(""))
                    .put("recertificationRequired", c.path("recertificationRequired").asBoolean(false))
                    .put("dlqOpen", store.listByRef(tenant, "dlq", c.get("id").asString()).stream().filter(x -> "OPEN".equals(x.path("status").asString())).count())
                    .put("lastRun", c.path("lastRun").path("status").asString("—"));
            String ep = c.path("endpointId").asString(null);
            if (ep != null) store.get(tenant, "endpoint", ep).ifPresent(e -> f.put("endpoint", e.path("name").asString()).put("endpointStatus", e.path("status").asString()).put("lastSeen", e.path("lastSeen").asString("")));
            String health = f.path("recertificationRequired").asBoolean() || "ISOLATED".equals(f.path("endpointStatus").asString()) ? "RED"
                    : "LOCKED".equals(f.get("calibration").asString()) || f.get("dlqOpen").asLong() > 0 ? "AMBER" : "GREEN";
            f.put("health", health);
            return f;
        }).toList();
    }
}
