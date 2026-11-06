package io.intimed.engine.pipeline;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.parse.ParseException;
import io.intimed.engine.parse.Parsed;
import io.intimed.engine.parse.ParserRegistry;
import io.intimed.engine.quality.QualityGates;
import io.intimed.engine.terminology.Terminology;
import io.intimed.events.EventBus;
import io.intimed.security.RateLimiter;
import io.intimed.security.endpoint.AnomalyService;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The canonical pipeline (IM-E01-F01, IM-E01-HLR-01): acquire → parse → normalise → associate → validate → route →
 * persist → acknowledge, identical for every device and connectivity option. Idempotent (stable observation ids: a
 * replayed message is acknowledged but not filed twice), dead-letter queue for unparseable messages, unmatched queue for
 * low-confidence association, holds for quality gates and verify-before-post, raw archive with SHA-256, per-stage timings.
 */
@Service
public class Pipeline {
    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);
    private final DocumentStore store;
    private final DriverRegistry drivers;
    private final ParserRegistry parsers;
    private final Terminology terms;
    private final AssociationEngine association;
    private final QualityGates gates;
    private final Router router;
    private final EventBus events;
    private final AnomalyService anomalies;
    private final RateLimiter limiter;
    private final TenantService tenants;

    public Pipeline(DocumentStore store, DriverRegistry drivers, ParserRegistry parsers, Terminology terms, AssociationEngine association,
                    QualityGates gates, Router router, EventBus events, AnomalyService anomalies, RateLimiter limiter, TenantService tenants) {
        this.store = store;
        this.drivers = drivers;
        this.parsers = parsers;
        this.terms = terms;
        this.association = association;
        this.gates = gates;
        this.router = router;
        this.events = events;
        this.anomalies = anomalies;
        this.limiter = limiter;
        this.tenants = tenants;
    }

    /** Platform-issued simulator / probe identities (harness probe, demo gateway). */
    static final java.util.Set<String> SIMULATOR_ENDPOINTS = java.util.Set.of("ep-harness", "ep-demo-gateway");

    public PipelineResult process(Envelope env) {
        long t0 = System.nanoTime();
        String tenant = env.tenant();
        JsonNode tenantSettings = tenants.require(tenant).settings();
        // IM-E09-BL-001: a trial tenant only accepts live traffic from the platform's own simulator identities
        if (!env.test() && tenantSettings.path("simulatorOnly").asBoolean(false) && env.endpointId() != null && !SIMULATOR_ENDPOINTS.contains(env.endpointId())) {
            return PipelineResult.of("REJECTED", "AR", "trial tenant accepts simulator traffic only");
        }
        ObjectNode config = store.get(tenant, "config", env.configId() == null ? "-" : env.configId()).orElse(null);
        if (config == null) return PipelineResult.of("REJECTED", "AR", "no configuration bound for this message");
        boolean published = "PUBLISHED".equals(config.path("status").asString());
        if (!env.test() && !published && !"SHADOW".equals(env.mode())) return PipelineResult.of("REJECTED", "AR", "configuration is not published");
        if ("DECOMMISSIONED".equals(config.path("status").asString())) return PipelineResult.of("REJECTED", "AR", "configuration decommissioned");
        JsonNode quotas = tenantSettings.path("quotas");
        if (!limiter.allow("tenant:" + tenant, quotas.path("messagesPerSecond").asDouble(500), quotas.path("burst").asDouble(1000))
                || (env.endpointId() != null && !limiter.allow("ep:" + env.endpointId(), config.path("params").path("maxMessagesPerSecond").asDouble(50), 100))) {
            if (env.endpointId() != null) anomalies.signal(tenant, env.endpointId(), "RATE_EXCEEDED", "config " + config.get("id").asString());
            return PipelineResult.of("REJECTED", "AR", "rate limit exceeded");
        }
        DriverPackage d = drivers.require(tenant, config.get("driver").asString());
        PipelineResult res = new PipelineResult();
        res.configId = config.get("id").asString();
        // acquire: raw archive
        String sha = Hashing.sha256(env.payload());
        res.rawSha256 = sha;
        store.put(tenant, "raw", sha, Json.obj().put("sha256", sha).put("payload", env.payload()).put("option", env.option())
                .put("configId", res.configId).put("receivedAt", env.receivedAt().toString()).put("mode", env.mode()), env.mode(), res.configId);
        long t1 = System.nanoTime();
        res.timingsMicros.put("acquire", (t1 - t0) / 1000);
        // parse
        List<Parsed> sets;
        try {
            sets = parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), env.payload());
        } catch (ParseException | IllegalArgumentException e) {
            res.status = "DLQ";
            res.ackCode = "AE";
            res.error = e.getMessage();
            res.dlqId = deadLetter(tenant, env, config, sha, "PARSE", e.getMessage());
            if (env.endpointId() != null && !env.test()) anomalies.signal(tenant, env.endpointId(), "PARSE_ERROR_BURST", e.getMessage());
            res.timingsMicros.put("parse", (System.nanoTime() - t1) / 1000);
            return res;
        }
        long t2 = System.nanoTime();
        res.timingsMicros.put("parse", (t2 - t1) / 1000);
        String worst = null;
        for (Parsed p : sets) {
            String st = processSet(env, config, d, p, res, sha);
            worst = worse(worst, st);
        }
        res.status = worst;
        if ("DLQ".equals(worst)) res.ackCode = "AE";
        res.timingsMicros.put("total", (System.nanoTime() - t0) / 1000);
        tenants.meter(tenant, env.test() ? "harness.messages" : "engine.messages", 1);
        return res;
    }

    private static final List<String> ORDER = List.of("FILED", "DUPLICATE", "HELD_VERIFY", "HELD_QUALITY", "UNMATCHED", "REJECTED", "DLQ");

    private static String worse(String a, String b) { return a == null ? b : ORDER.indexOf(b) > ORDER.indexOf(a) ? b : a; }

    private String processSet(Envelope env, ObjectNode config, DriverPackage d, Parsed p, PipelineResult res, String sha) {
        String tenant = env.tenant();
        long t0 = System.nanoTime();
        // normalise
        ObjectNode obs;
        try {
            obs = normalise(env, config, d, p);
        } catch (IllegalArgumentException e) {
            res.dlqId = deadLetter(tenant, env, config, sha, "NORMALISE", e.getMessage());
            res.error = e.getMessage();
            return "DLQ";
        }
        long t1 = System.nanoTime();
        res.timingsMicros.merge("normalise", (t1 - t0) / 1000, Long::sum);
        // identity binding (XT-02): device serial must be one of the configuration's devices when declared
        String serial = obs.path("deviceSerial").asString("");
        JsonNode expected = config.path("expectedSerials");
        if (!env.test() && expected.isArray() && !expected.isEmpty() && !serial.isEmpty()) {
            boolean ok = false;
            for (JsonNode s : expected) ok |= s.asString().equals(serial);
            if (!ok) {
                if (env.endpointId() != null) anomalies.signal(tenant, env.endpointId(), "NEW_DEVICE_SERIAL", "serial not bound to " + config.get("id").asString());
                res.error = "device serial not bound to configuration";
                res.ackCode = "AR";
                return "REJECTED";
            }
        }
        // duplicate (idempotency)
        String id = obs.get("id").asString();
        if (store.get(tenant, "observation", id).isPresent()) {
            if (env.endpointId() != null && !env.test()) anomalies.signal(tenant, env.endpointId(), "DUPLICATE_BURST", "duplicate " + id);
            res.observationIds.add(id);
            return "DUPLICATE";
        }
        if (!env.test() && env.endpointId() != null && "SPOT".equals(config.path("captureMode").asString())) {
            anomalies.signal(tenant, env.endpointId(), "VALUE_BURST", "spot observation");
        }
        // associate
        AssociationEngine.Result a = association.associate(tenant, config, p.identity, env.testSubject(), env.receivedAt(), env.test());
        double threshold = config.path("association").path("threshold").asDouble(0.95);
        long t2 = System.nanoTime();
        res.timingsMicros.merge("associate", (t2 - t1) / 1000, Long::sum);
        res.association = a.method();
        res.confidence = a.confidence();
        obs.putObject("subject").put("ref", a.subject()).put("method", a.method()).put("confidence", a.confidence()).put("detail", a.detail());
        if ("NO_CONSENT".equals(a.consentScope())) {
            obs.put("status", "REJECTED_NO_CONSENT");
            obs.remove("measurements");
            store.put(tenant, "observation", id, obs, "REJECTED", config.get("id").asString());
            res.observationIds.add(id);
            res.error = "consent missing or withdrawn – data not stored";
            return "REJECTED";
        }
        if (!a.matched(threshold)) {
            String uid = Hashing.id("unm");
            ObjectNode u = Json.obj().put("id", uid).put("observationId", id).put("configId", config.get("id").asString())
                    .put("station", config.at("/site/station").asString("")).put("reason", a.detail()).put("method", a.method())
                    .put("confidence", a.confidence()).put("suggestion", a.suggestion()).put("typedIdentifier", p.identity.getOrDefault("subject", ""))
                    .put("createdAt", Instant.now().toString()).put("status", "OPEN").put("mode", env.mode()).put("runId", env.runId());
            obs.put("status", "UNMATCHED");
            store.put(tenant, "observation", id, obs, "UNMATCHED", config.get("id").asString());
            store.put(tenant, "unmatched", uid, u, "OPEN", config.at("/site/station").asString(""));
            res.unmatchedId = uid;
            res.observationIds.add(id);
            res.observations.add(obs);
            events.publish(tenant, "observation.unmatched", Json.obj().put("observation", id).put("config", config.get("id").asString()));
            return "UNMATCHED";
        }
        // validate
        QualityGates.Outcome q = gates.evaluate(config, obs, p.extras, env.receivedAt());
        obs.set("quality", q.quality());
        long t3 = System.nanoTime();
        res.timingsMicros.merge("validate", (t3 - t2) / 1000, Long::sum);
        String status;
        if (q.hold() != null) {
            status = "VERIFY".equals(q.hold()) ? "HELD_VERIFY" : "HELD_QUALITY";
            obs.put("status", status).put("holdReason", q.hold());
            events.publish(tenant, "observation.held", Json.obj().put("observation", id).put("reason", q.hold()));
        } else {
            String routeMode = shadowActive(config, env) ? "SHADOW" : env.mode();
            if ("SHADOW".equals(routeMode)) obs.put("shadow", true);
            router.route(tenant, config, obs, routeMode, env.runId(), res);
            status = "FILED";
            obs.put("status", "FILED");
        }
        long t4 = System.nanoTime();
        res.timingsMicros.merge("route", (t4 - t3) / 1000, Long::sum);
        store.put(tenant, "observation", id, obs, obs.get("status").asString(), config.get("id").asString());
        res.timingsMicros.merge("persist", (System.nanoTime() - t4) / 1000, Long::sum);
        res.observationIds.add(id);
        res.observations.add(obs);
        if ("FILED".equals(status)) {
            events.publish(tenant, "observation.posted", Json.obj().put("observation", id).put("config", config.get("id").asString()).put("mode", env.mode())
                    .set("instances", Json.tree(res.instanceIds)));
            tenants.meter(tenant, "observations.filed", 1);
        }
        return status;
    }

    /** Shadow mode (IM-E04-F05): before clinical sign-off live data is filed into shadow copies of the template only. */
    public static boolean shadowActive(JsonNode config, Envelope env) {
        JsonNode sh = config.path("shadow");
        return "LIVE".equals(env.mode()) && sh.isObject() && !sh.path("signedOff").asBoolean(false)
                && Instant.parse(sh.path("until").asString("1970-01-01T00:00:00Z")).isAfter(env.receivedAt());
    }

    /** Files a held observation after clinician verification (capture accept) – optionally with corrected values. */
    public PipelineResult releaseHeld(String tenant, String observationId, Map<String, BigDecimal> corrections, String actor) {
        ObjectNode obs = store.require(tenant, "observation", observationId);
        if (!obs.path("status").asString().startsWith("HELD")) throw new IllegalStateException("observation is not held");
        if ("HELD_QUALITY".equals(obs.get("status").asString())) throw new SecurityException("quality hold (calibration/QC) cannot be overridden at capture");
        ObjectNode config = store.require(tenant, "config", obs.get("configId").asString());
        for (JsonNode m : obs.get("measurements")) {
            BigDecimal c = corrections == null ? null : corrections.get(m.get("key").asString());
            if (c != null) ((ObjectNode) m).put("originalCaptured", m.get("value").asString()).put("value", c).put("corrected", true);
        }
        obs.put("verifiedBy", actor).put("verifiedAt", Instant.now().toString());
        PipelineResult res = new PipelineResult();
        res.configId = config.get("id").asString();
        router.route(tenant, config, obs, obs.path("mode").asString("LIVE"), obs.path("runId").asString(null), res);
        obs.put("status", "FILED");
        store.put(tenant, "observation", observationId, obs, "FILED", config.get("id").asString());
        res.status = "FILED";
        res.observationIds.add(observationId);
        events.publish(tenant, "observation.posted", Json.obj().put("observation", observationId).put("config", res.configId).put("verified", true));
        return res;
    }

    /**
     * Dry run (IM-E14 validation studio / cohort simulator): parse → normalise → map → annotate with reference intervals,
     * with no persistence, no association, no metering and no events. {@code templateIds} null = every template the
     * driver package binds. Demographics come from the request, not the subject registry.
     */
    public ObjectNode dryRun(String tenant, DriverPackage d, String payload, List<String> templateIds,
                             java.util.function.Function<String, JsonNode> templateLookup, io.intimed.clinical.Demographics demo) {
        return dryRun(tenant, d, payload, templateIds, templateLookup, demo, false);
    }

    /** Dry run with the tenant's interval overlay; {@code derive} adds derived values (IM-E14-BL-010) to each mapped template. */
    public ObjectNode dryRun(String tenant, DriverPackage d, String payload, List<String> templateIds,
                             java.util.function.Function<String, JsonNode> templateLookup, io.intimed.clinical.Demographics demo, boolean derive) {
        ObjectNode out = Json.obj().put("driver", d.ref()).put("grammar", d.grammar().get("type").asString());
        ObjectNode cfg = Json.obj().put("id", "dry-run").put("hash", "dry-run");
        List<Parsed> sets;
        try {
            sets = parsers.get(d.grammar().get("type").asString()).parse(d.grammar(), payload);
        } catch (ParseException | IllegalArgumentException e) {
            return out.put("status", "PARSE_ERROR").put("error", e.getMessage());
        }
        ArrayNode results = out.putArray("sets");
        Envelope env = new Envelope(tenant, null, "dry-run", d.options().isEmpty() ? "P?" : d.options().get(0), payload, "TEST", null, "dry-run", "dry-run", Instant.now());
        for (Parsed p : sets) {
            ObjectNode r = results.addObject();
            r.set("identity", Json.tree(maskIdentity(p.identity)));
            r.set("warnings", Json.tree(p.warnings));
            ObjectNode obs;
            try { obs = normalise(env, cfg, d, p); }
            catch (IllegalArgumentException e) { r.put("status", "NORMALISE_ERROR").put("error", e.getMessage()); continue; }
            r.set("measurements", obs.get("measurements"));
            ArrayNode mapped = r.putArray("mapped");
            d.bindings().properties().forEach(b -> {
                if (templateIds != null && !templateIds.contains(b.getKey())) return;
                JsonNode tpl = templateLookup.apply(b.getKey());
                if (tpl != null) mapped.add(router.dryMap(tenant, b.getValue(), tpl, obs, demo, derive));
            });
            r.put("status", "MAPPED");
        }
        out.put("status", results.isEmpty() ? "EMPTY" : "OK");
        return out;
    }

    private static Map<String, String> maskIdentity(Map<String, String> id) {
        Map<String, String> m = new java.util.LinkedHashMap<>(id);
        m.computeIfPresent("subject", (k, v) -> PhiMasker.mask(v));
        return m;
    }

    ObjectNode normalise(Envelope env, JsonNode config, DriverPackage d, Parsed p) {
        ObjectNode obs = Json.obj();
        Instant eff = time(p.identity.get("time"), d.grammar().path("identity").path("timeFormat").asString(null), env.receivedAt());
        ArrayNode ms = obs.putArray("measurements");
        StringBuilder material = new StringBuilder(config.get("id").asString()).append('|').append(env.mode()).append('|').append(env.runId() == null ? "" : env.runId())
                .append('|').append(p.identity.getOrDefault("serial", "")).append('|').append(eff);
        for (var e : p.values.entrySet()) {
            JsonNode sem = d.semantic(e.getKey());
            if (sem == null) continue;
            ObjectNode m = ms.addObject().put("key", e.getKey()).put("display", sem.path("display").asString());
            ObjectNode code = m.putObject("code");
            for (String c : List.of("mdc", "loinc", "snomed", "local")) if (sem.has(c)) code.put(c, sem.get(c).asString());
            if ("text".equals(sem.path("type").asString())) {
                m.put("value", e.getValue().value());
            } else {
                BigDecimal v;
                try { v = new BigDecimal(e.getValue().value().replace(',', '.')); }
                catch (NumberFormatException ex) { throw new IllegalArgumentException("non-numeric value for " + e.getKey()); }
                String from = terms.ucum(e.getValue().unit() != null ? e.getValue().unit() : sem.path("ucum").asString(null));
                String to = sem.path("ucum").asString(from);
                java.util.Map<String, Double> factors = new java.util.HashMap<>();
                sem.path("factors").properties().forEach(f -> factors.put(f.getKey(), f.getValue().asDouble()));
                var conv = terms.convert(v, from, to, factors);
                if (conv.isEmpty()) throw new IllegalArgumentException("unit " + from + " not convertible to " + to + " for " + e.getKey());
                m.put("value", conv.get()).put("unit", to);
                if (from != null && !from.equals(to)) m.putObject("original").put("value", v).put("unit", from);
                if (sem.has("plausible")) m.set("plausible", sem.get("plausible"));
                if (sem.has("artefactJumpPct")) m.put("artefactJumpPct", sem.get("artefactJumpPct").asDouble());
                if (sem.has("factors")) m.set("factors", sem.get("factors"));
            }
            if (sem.has("method")) m.put("method", sem.get("method").asString());
            if (sem.has("bodySite")) m.put("bodySite", sem.get("bodySite").asString());
            m.put("status", "final");
            material.append('|').append(e.getKey()).append('=').append(e.getValue().value());
        }
        if (ms.isEmpty()) throw new IllegalArgumentException("no measurement maps to the driver semantics");
        String id = Hashing.stableId("obs", material.toString());
        obs.put("id", id).put("tenant", env.tenant()).put("configId", config.get("id").asString()).put("modelId", config.path("modelId").asString())
                .put("deviceSerial", p.identity.getOrDefault("serial", "")).put("endpointId", env.endpointId()).put("option", env.option())
                .put("configHash", config.path("hash").asString("")).put("effectiveTime", eff.toString()).put("receivedAt", env.receivedAt().toString())
                .put("mode", env.mode()).put("runId", env.runId());
        obs.putObject("driver").put("ref", d.ref()).put("signature", d.signatureStatus()).put("publisher", d.publisher());
        obs.putObject("context").put("site", config.at("/site/site").asString("")).put("station", config.at("/site/station").asString(""))
                .put("bed", config.at("/site/bed").asString(""));
        if (!p.extras.isEmpty()) obs.set("messageContext", Json.tree(maskExtras(p.extras)));
        return obs;
    }

    private static Map<String, String> maskExtras(Map<String, String> ex) {
        Map<String, String> m = new java.util.LinkedHashMap<>(ex);
        m.computeIfPresent("attestation", (k, v) -> PhiMasker.mask(v));
        return m;
    }

    static Instant time(String s, String fmt, Instant fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Instant.parse(s); } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC); } catch (Exception ignored) { }
        if (fmt != null) {
            try {
                if (fmt.endsWith("Z")) return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern(fmt)).toInstant();
                return LocalDateTime.parse(s.length() > fmt.length() ? s.substring(0, fmt.length()) : s, DateTimeFormatter.ofPattern(fmt)).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) { }
        }
        LOG.debug("unparseable device time – using receipt time");
        return fallback;
    }

    String deadLetter(String tenant, Envelope env, JsonNode config, String sha, String stage, String error) {
        String id = Hashing.id("dlq");
        store.put(tenant, "dlq", id, Json.obj().put("id", id).put("stage", stage).put("error", error).put("configId", config.get("id").asString())
                .put("rawSha256", sha).put("traceMasked", PhiMasker.mask(env.payload())).put("option", env.option()).put("mode", env.mode())
                .put("runId", env.runId()).put("endpointId", env.endpointId()).put("createdAt", Instant.now().toString()).put("status", "OPEN"), "OPEN", config.get("id").asString());
        return id;
    }

    /** DLQ replay (after a driver fix) – idempotency guarantees no duplicates. */
    public PipelineResult replay(String tenant, String dlqId, String actor) {
        ObjectNode q = store.require(tenant, "dlq", dlqId);
        ObjectNode raw = store.require(tenant, "raw", q.get("rawSha256").asString());
        PipelineResult r = process(new Envelope(tenant, q.path("endpointId").asString(null), q.get("configId").asString(), q.path("option").asString(),
                raw.get("payload").asString(), q.path("mode").asString("LIVE"), null, q.path("runId").asString(null), "replay", Instant.now()));
        q.put("status", "DLQ".equals(r.status) ? "OPEN" : "REPLAYED").put("replayedAt", Instant.now().toString()).put("replayedBy", actor).put("replayResult", r.status);
        store.put(tenant, "dlq", dlqId, q, q.get("status").asString(), q.get("configId").asString());
        return r;
    }

    /** Unmatched queue resolution by a clinician: assign subject → validate → route. */
    public PipelineResult resolveUnmatched(String tenant, String unmatchedId, String subject, String actor) {
        ObjectNode u = store.require(tenant, "unmatched", unmatchedId);
        ObjectNode obs = store.require(tenant, "observation", u.get("observationId").asString());
        ObjectNode config = store.require(tenant, "config", obs.get("configId").asString());
        obs.putObject("subject").put("ref", subject).put("method", "MANUAL_RESOLUTION").put("confidence", 1.0).put("detail", "resolved by " + actor);
        QualityGates.Outcome q = gates.evaluate(config, obs, Map.of(), Instant.parse(obs.get("receivedAt").asString()));
        obs.set("quality", q.quality());
        PipelineResult res = new PipelineResult();
        res.configId = config.get("id").asString();
        if (q.hold() != null) {
            obs.put("status", "VERIFY".equals(q.hold()) ? "HELD_VERIFY" : "HELD_QUALITY");
        } else {
            router.route(tenant, config, obs, obs.path("mode").asString("LIVE"), obs.path("runId").asString(null), res);
            obs.put("status", "FILED");
        }
        store.put(tenant, "observation", obs.get("id").asString(), obs, obs.get("status").asString(), config.get("id").asString());
        u.put("status", "RESOLVED").put("resolvedBy", actor).put("resolvedAt", Instant.now().toString());
        store.put(tenant, "unmatched", unmatchedId, u, "RESOLVED", u.path("station").asString(""));
        res.status = obs.get("status").asString();
        res.observationIds.add(obs.get("id").asString());
        return res;
    }
}
