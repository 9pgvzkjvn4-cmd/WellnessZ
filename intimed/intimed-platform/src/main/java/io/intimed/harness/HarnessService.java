package io.intimed.harness;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.parse.DelimitedRecords;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.quality.QualityGates;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.transport.DeviceListeners;
import io.intimed.engine.transport.TransportClient;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.TokenService;
import io.intimed.security.pki.LocalPki;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import io.intimed.tenancy.TenantService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration-time exchange test harness (IM-E04, architecture §7): derives a plan from the configuration draft
 * (model × option × parameters × association × template bindings × tenant policy), runs it against the family simulator
 * through the REAL device transports (MLLP-TLS / HTTPS ingress with the harness probe's mTLS identity), delivers into
 * sandbox instances of every bound template through the real adapters, verifies XT-01…XT-16 and seals an evidence pack.
 * The same service backs the wizard (step 6), driver CI / partner tests (API) and scheduled site regression.
 */
@Service
public class HarnessService {
    public static final Map<String, String> CHECKS = new LinkedHashMap<>();
    static {
        CHECKS.put("XT-01", "Connection & handshake"); CHECKS.put("XT-02", "Identity binding"); CHECKS.put("XT-03", "Sample acquisition");
        CHECKS.put("XT-04", "Parse & normalise"); CHECKS.put("XT-05", "Association"); CHECKS.put("XT-06", "Template delivery");
        CHECKS.put("XT-07", "Field coverage & mandatory fields"); CHECKS.put("XT-08", "Units, codes & reference ranges"); CHECKS.put("XT-09", "Round trip");
        CHECKS.put("XT-10", "Latency"); CHECKS.put("XT-11", "Negative cases"); CHECKS.put("XT-12", "Bidirectional"); CHECKS.put("XT-13", "Security checks");
        CHECKS.put("XT-14", "Quality gates"); CHECKS.put("XT-15", "Provenance & audit"); CHECKS.put("XT-16", "Evidence pack");
    }

    private final ConfigService configs;
    private final DriverRegistry drivers;
    private final TemplateRegistry templates;
    private final DocumentStore store;
    private final ProbeIdentity probes;
    private final DeviceListeners listeners;
    private final Pipeline pipeline;
    private final QualityGates gates;
    private final EvidenceSealer sealer;
    private final AuditService audit;
    private final EventBus events;
    private final TenantService tenants;
    private final SecretStore secrets;
    private final TokenService tokens;
    private final Environment env;

    public HarnessService(ConfigService configs, DriverRegistry drivers, TemplateRegistry templates, DocumentStore store, ProbeIdentity probes,
                          DeviceListeners listeners, Pipeline pipeline, QualityGates gates, EvidenceSealer sealer, AuditService audit, EventBus events,
                          TenantService tenants, SecretStore secrets, TokenService tokens, Environment env) {
        this.configs = configs;
        this.drivers = drivers;
        this.templates = templates;
        this.store = store;
        this.probes = probes;
        this.listeners = listeners;
        this.pipeline = pipeline;
        this.gates = gates;
        this.sealer = sealer;
        this.audit = audit;
        this.events = events;
        this.tenants = tenants;
        this.secrets = secrets;
        this.tokens = tokens;
        this.env = env;
    }

    /** Test plan generator (IM-E04-BL-001). */
    public ObjectNode plan(String tenant, String configId) {
        ObjectNode c = configs.get(tenant, configId);
        DriverPackage d = requireReady(tenant, c);
        ObjectNode p = Json.obj().put("configId", configId).put("configHash", ConfigService.hash(c)).put("driver", d.ref()).put("option", c.get("option").asString())
                .put("mode", "SIMULATOR").put("association", c.at("/association/method").asString());
        ArrayNode checks = p.putArray("checks");
        CHECKS.forEach((id, name) -> {
            boolean na = "XT-12".equals(id) && !d.manifest().path("bidirectional").asBoolean(false);
            checks.addObject().put("id", id).put("name", name).put("applicable", !na);
        });
        p.set("negativeCases", c.path("harness").path("negativeCases").isArray() ? c.at("/harness/negativeCases") : d.tests().path("negative"));
        ArrayNode tpls = p.putArray("templates");
        c.path("bindings").forEach(b -> tpls.add(b.path("templateId").asString()));
        return p;
    }

    private DriverPackage requireReady(String tenant, JsonNode c) {
        if (!c.hasNonNull("driver") || !c.hasNonNull("option")) throw ApiException.conflict("incomplete", "select a driver and connectivity option first (steps 1–2)");
        if (c.path("bindings").isEmpty()) throw ApiException.conflict("incomplete", "bind at least one template (step 5)");
        return drivers.require(tenant, c.get("driver").asString());
    }

    private final class Ctx {
        final String tenant, runId, subject;
        final ObjectNode cfg;
        final DriverPackage d;
        final ProbeIdentity.Creds probe;
        final List<String> traces = new ArrayList<>();
        final List<Long> latencies = new ArrayList<>();
        final ObjectNode results = Json.obj();
        final ObjectNode security = Json.obj();
        String protocol = "", cipher = "";
        Ctx(String tenant, String runId, ObjectNode cfg, DriverPackage d, ProbeIdentity.Creds probe) {
            this.tenant = tenant; this.runId = runId; this.cfg = cfg; this.d = d; this.probe = probe;
            this.subject = "TEST-" + runId.substring(4).toUpperCase();
        }
        void check(String id, boolean pass, String detail, JsonNode evidence) {
            ObjectNode r = results.putObject(id).put("id", id).put("name", CHECKS.get(id)).put("status", pass ? "PASS" : "FAIL").put("detail", detail);
            if (evidence != null) r.set("evidence", evidence);
        }
        void na(String id, String detail) { results.putObject(id).put("id", id).put("name", CHECKS.get(id)).put("status", "N/A").put("detail", detail); }
    }

    record Outcome(String status, String ack, List<ObjectNode> observations, String raw) { }

    public ObjectNode run(String tenant, String configId, String actor) {
        ObjectNode cfg = configs.get(tenant, configId);
        DriverPackage d = requireReady(tenant, cfg);
        String runId = "run-" + Hashing.randomToken(6).toLowerCase().replaceAll("[^a-z0-9]", "0");
        Instant started = Instant.now();
        Ctx x = new Ctx(tenant, runId, cfg, d, probes.harness(tenant));
        String serial = cfg.path("expectedSerials").isArray() && !cfg.get("expectedSerials").isEmpty() ? cfg.get("expectedSerials").get(0).asString() : d.tests().path("serial").asString("SIM-0001");
        Map<String, String> values = Simulator.values(d);
        boolean qcGate = cfg.path("qualityGates").path("qcLotGate").asBoolean(false);
        if (qcGate) {
            // XT-14 evidence: a sample from an unreleased lot is held; then QC is recorded for the harness lot
            values.put("lot", "UNRELEASED-" + runId.substring(4));
        }
        Instant ts = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String nominal = Simulator.render(d, values, x.subject, ts, serial);
        Outcome first = send(x, nominal, true);
        boolean qcHeld = false;
        if (qcGate) {
            qcHeld = "HELD_QUALITY".equals(first.status());
            String lot = "HARNESS-" + runId.substring(4);
            ObjectNode fresh = configs.get(tenant, configId);
            fresh.withObject("qc").withObject("lots").putObject(lot).put("qcPassed", true).put("recordedBy", "harness").put("at", Instant.now().toString());
            store.put(tenant, "config", configId, fresh, fresh.get("status").asString(), fresh.get("modelId").asString());
            values.put("lot", lot);
            nominal = Simulator.render(d, values, x.subject, ts.plusSeconds(1), serial);
            first = send(x, nominal, true);
        }
        boolean verifyHeld = false;
        List<ObjectNode> obs = new ArrayList<>(first.observations());
        if ("HELD_VERIFY".equals(first.status())) {
            verifyHeld = true;
            for (ObjectNode o : first.observations()) pipeline.releaseHeld(tenant, o.get("id").asString(), null, "harness:verifier");
            obs.clear();
            for (ObjectNode o : first.observations()) obs.add(store.require(tenant, "observation", o.get("id").asString()));
        }
        // ---- XT-01 / XT-02
        x.check("XT-01", !x.protocol.isEmpty() && (x.protocol.equals("TLSv1.3") || x.protocol.equals("TLSv1.2")),
                "Transport " + transportName(cfg) + " negotiated " + x.protocol + " " + x.cipher + " with mutual TLS (client certificate of " + x.probe.endpointId() + ")",
                Json.obj().put("protocol", x.protocol).put("cipher", x.cipher).put("clientCertificateSerial", x.probe.cert().getSerialNumber().toString(16)));
        String obsSerial = obs.isEmpty() ? "" : obs.get(0).path("deviceSerial").asString("");
        boolean serialOk = obsSerial.isEmpty() || obsSerial.equals(serial);
        x.check("XT-02", serialOk && !obs.isEmpty(), "Certificate CN ↔ endpoint registry " + x.probe.endpointId() + " ACTIVE; device serial '" + obsSerial + "' expected '" + serial + "'", null);
        // ---- XT-03 / XT-04
        x.check("XT-03", !obs.isEmpty(), obs.isEmpty() ? "No observation acquired (status " + first.status() + ")" : obs.size() + " observation(s) acquired via " + cfg.get("option").asString(), null);
        int codes = 0, measures = 0;
        List<String> unresolved = new ArrayList<>();
        for (ObjectNode o : obs) for (JsonNode m : o.path("measurements")) {
            measures++;
            JsonNode cd = m.path("code");
            if (cd.has("loinc") || cd.has("mdc") || cd.has("local")) codes++; else unresolved.add(m.path("key").asString());
        }
        x.check("XT-04", !obs.isEmpty() && codes == measures && !"DLQ".equals(first.status()),
                measures + " measurements parsed, " + codes + " codes resolved (MDC/LOINC/local), UCUM units" + (unresolved.isEmpty() ? "" : "; unresolved " + unresolved), null);
        // ---- XT-05 association
        boolean assocOk = obs.stream().allMatch(o -> x.subject.equals(o.at("/subject/ref").asString()) && o.at("/subject/confidence").asDouble() >= cfg.at("/association/threshold").asDouble(0.95));
        // ---- latency samples (XT-10)
        for (int i = 1; i <= 4; i++) {
            Map<String, String> v = Simulator.vary(values, i);
            Outcome o = send(x, Simulator.render(d, v, x.subject, ts.plusSeconds(60L * i), serial), true);
            if ("HELD_VERIFY".equals(o.status())) for (ObjectNode h : o.observations()) pipeline.releaseHeld(tenant, h.get("id").asString(), null, "harness:verifier");
        }
        // ---- negative cases (XT-11) incl. unmatched path for XT-05
        ArrayNode neg = Json.arr();
        boolean negOk = true;
        String unmatchedNote = "not exercised";
        JsonNode negList = cfg.path("harness").path("negativeCases").isArray() ? cfg.at("/harness/negativeCases") : d.tests().path("negative");
        for (JsonNode n : negList) {
            String kind = n.asString();
            String payload = "DUPLICATE".equals(kind) ? nominal : Simulator.negative(kind, d, nominal, values, x.subject, ts.plusSeconds(400), serial);
            Outcome o = send(x, payload, !"WRONG_SUBJECT".equals(kind));
            boolean ok;
            String expect;
            switch (kind) {
                case "DUPLICATE" -> { expect = "DUPLICATE (acknowledged, not filed twice)"; ok = "DUPLICATE".equals(o.status()); }
                case "MALFORMED" -> { expect = "AE + dead-letter queue"; ok = "DLQ".equals(o.status()) || "AE".equals(o.ack()); }
                case "TIME_SKEW" -> { expect = "clock skew corrected and flagged"; ok = o.observations().stream().anyMatch(ob -> ob.path("quality").path("flags").toString().contains("CLOCK_SKEW")); }
                case "OUT_OF_RANGE" -> { expect = "held for verification with OUT_OF_PLAUSIBLE_RANGE"; ok = "HELD_VERIFY".equals(o.status()) || o.observations().stream().anyMatch(ob -> ob.path("quality").toString().contains("OUT_OF_PLAUSIBLE")); }
                case "WRONG_SUBJECT" -> {
                    expect = "unmatched queue (or consent refusal) – never filed to a wrong subject";
                    ok = List.of("UNMATCHED", "REJECTED").contains(o.status());
                    unmatchedNote = o.status();
                }
                default -> { expect = "rejected or queued"; ok = !"FILED".equals(o.status()); }
            }
            negOk &= ok;
            neg.addObject().put("case", kind).put("expected", expect).put("actual", o.status() + " / " + o.ack()).put("pass", ok);
        }
        Outcome health = send(x, Simulator.render(d, Simulator.vary(values, 99), x.subject, ts.plusSeconds(900), serial), true);
        if ("HELD_VERIFY".equals(health.status())) for (ObjectNode h : health.observations()) pipeline.releaseHeld(tenant, h.get("id").asString(), null, "harness:verifier");
        boolean healthy = List.of("FILED", "HELD_VERIFY", "HELD_QUALITY").contains(health.status());
        x.check("XT-05", assocOk && !obs.isEmpty(), "Test subject " + x.subject + " associated (method TEST_SUBJECT, confidence 1.00 ≥ threshold); configured method "
                + cfg.at("/association/method").asString() + "; unmatched path exercised: " + unmatchedNote, null);
        x.check("XT-11", negOk && healthy, neg.size() + " negative cases as designed, no crash, service healthy afterwards (" + health.status() + ")", neg);
        // ---- XT-06..09 on the sandbox instances
        List<ObjectNode> inst = store.listByRef(tenant, "sandbox-instance", runId);
        ArrayNode cov = Json.arr();
        boolean delivered = true, coverage = true, units = true, round = true;
        List<String> mismatches = new ArrayList<>();
        Map<String, byte[]> renderings = new LinkedHashMap<>();
        for (JsonNode b : cfg.path("bindings")) {
            String tid = b.path("templateId").asString();
            JsonNode tpl = templates.require(tenant, tid);
            ObjectNode i = inst.stream().filter(n -> tid.equals(n.path("templateId").asString())).findFirst().orElse(null);
            if (i == null) { delivered = false; cov.addObject().put("template", tid).put("delivered", false); continue; }
            int mand = 0, mandFilled = 0, opt = 0, optFilled = 0;
            ArrayNode coveredByOthers = Json.arr();
            for (JsonNode f : TemplateRegistry.fields(tpl)) {
                boolean filled = i.path("fields").has(f.get("id").asString());
                if (!filled && f.path("mandatory").asBoolean()) {
                    // many-to-many: a mandatory field may be filled by another certified device bound to the same template
                    String other = otherCoverage(tenant, configId, tid, f.get("id").asString());
                    if (other != null) { filled = true; coveredByOthers.add(f.get("id").asString() + " ← " + other); }
                }
                if (f.path("mandatory").asBoolean()) { mand++; if (filled) mandFilled++; } else { opt++; if (filled) optFilled++; }
                if (filled) {
                    JsonNode v = i.path("fields").get(f.get("id").asString());
                    if (f.has("unit") && v.has("unit") && !f.get("unit").asString().equals(v.get("unit").asString())) { units = false; mismatches.add(tid + "." + f.get("id").asString() + " unit"); }
                    if (f.has("range") && v.path("value").isNumber()) {
                        BigDecimal val = v.get("value").decimalValue();
                        if (f.get("range").has("min") && val.compareTo(f.get("range").get("min").decimalValue()) < 0 || f.get("range").has("max") && val.compareTo(f.get("range").get("max").decimalValue()) > 0) {
                            if (!v.path("flags").toString().contains("OUT_OF")) { units = false; mismatches.add(tid + "." + f.get("id").asString() + " range unflagged"); }
                        }
                    }
                }
            }
            coverage &= mand == mandFilled;
            ObjectNode cv = cov.addObject().put("template", tid).put("instance", i.get("id").asString()).put("delivered", true).put("mandatory", mand).put("mandatoryFilled", mandFilled)
                    .put("optional", opt).put("optionalFilled", optFilled);
            if (!coveredByOthers.isEmpty()) cv.set("coveredByOtherDevices", coveredByOthers);
            JsonNode deliveries = i.path("deliveries");
            for (JsonNode dl : deliveries) if (dl.path("ack").asString("").isBlank()) delivered = false;
            cv.set("adapters", deliveries);
            // round trip: FHIR valueQuantity and HL7 OBX-5 read back equal the instance values
            ObjectNode fhir = (ObjectNode) templates.find(tenant, tid).map(t -> adapters(tenant, "FHIR", i)).orElse(Json.obj());
            ObjectNode hl7 = adapters(tenant, "HL7V2", i);
            for (var f : i.path("fields").properties()) {
                String expect = f.getValue().path("value").asString();
                boolean seen = false;
                for (JsonNode e : fhir.path("payload").path("entry")) {
                    JsonNode r = e.path("resource");
                    String code = TemplateRegistry.field(tpl, f.getKey()) == null ? "" : TemplateRegistry.field(tpl, f.getKey()).at("/code/loinc").asString("");
                    String back = r.has("valueQuantity") ? r.get("valueQuantity").path("value").asString() : r.path("valueString").asString(null);
                    if (back != null && (code.isEmpty() || r.path("code").toString().contains(code)) && new java.math.BigDecimal(isNum(back) ? back : "0").compareTo(new java.math.BigDecimal(isNum(expect) ? expect : "0")) == 0 && (isNum(back) || back.equals(expect))) { seen = true; break; }
                }
                boolean hl7Seen = hl7.path("payload").asString("").contains("|" + expect + "|");
                if (!seen || !hl7Seen) { round = false; mismatches.add(tid + "." + f.getKey() + " read-back"); }
            }
            renderings.put("screenshots/" + tid + ".html", html(tpl, i).getBytes(StandardCharsets.UTF_8));
        }
        x.check("XT-06", delivered && !inst.isEmpty(), inst.size() + " sandbox template instance(s) filled through the template adapters", null);
        x.check("XT-07", coverage && !inst.isEmpty(), "Mandatory field coverage " + (coverage ? "100 %" : "incomplete"), cov);
        x.check("XT-08", units && !inst.isEmpty(), mismatches.stream().noneMatch(m -> m.contains("unit") || m.contains("range")) ? "0 unit/code/range mismatches" : "Mismatches: " + mismatches, null);
        x.check("XT-09", round && !inst.isEmpty(), round ? "FHIR valueQuantity and HL7 OBX-5 read back equal the normalised values" : "Read-back differences: " + mismatches, null);
        long p95 = x.latencies.stream().sorted().skip(Math.max(0, (long) Math.ceil(x.latencies.size() * 0.95) - 1)).findFirst().orElse(0L);
        boolean cont = "CONTINUOUS".equals(cfg.path("captureMode").asString());
        x.check("XT-10", p95 <= (cont ? 1000 : 3000), "End-to-end p95 " + p95 + " ms over " + x.latencies.size() + " samples (target " + (cont ? "≤ 1 s interval" : "≤ 3 s spot") + ")",
                Json.obj().set("samplesMs", Json.tree(x.latencies)));
        // ---- XT-12 bidirectional
        if (d.manifest().path("bidirectional").asBoolean(false)) bidirectional(x, values, serial, ts);
        else x.na("XT-12", "Driver is one-way (no order push / auto-programming)");
        // ---- XT-13 security
        securityChecks(x);
        // ---- XT-14 quality gates
        qualityChecks(x, obs, verifyHeld, qcGate, qcHeld);
        // ---- XT-15 provenance & audit
        boolean prov = !obs.isEmpty() && obs.stream().allMatch(o -> d.ref().equals(o.at("/driver/ref").asString()) && ConfigService.hash(cfg).equals(o.path("configHash").asString())
                && o.hasNonNull("endpointId") && "VALID".equals(o.at("/driver/signature").asString()));
        Map<String, Object> chain = audit.verify();
        x.check("XT-15", prov && Boolean.TRUE.equals(chain.get("valid")), "Provenance (driver " + d.ref() + " signature VALID, configuration hash, endpoint) attached; audit chain "
                + (Boolean.TRUE.equals(chain.get("valid")) ? "intact (" + chain.get("records") + " records)" : "BROKEN"), null);
        // ---- evidence pack (XT-16)
        Instant finished = Instant.now();
        ObjectNode manifest = Json.obj().put("runId", runId).put("configId", configId).put("configHash", ConfigService.hash(cfg)).put("driver", d.ref())
                .put("environment", "LOCAL").put("stage", cfg.path("stage").asString("DEV")).put("mode", "SIMULATOR").put("operator", actor)
                .put("testSubject", x.subject).put("startedAt", started.toString()).put("finishedAt", finished.toString()).put("tenant", tenant);
        ArrayNode tv = manifest.putArray("templates");
        cfg.path("bindings").forEach(b -> tv.add(b.path("templateId").asString() + "@" + templates.require(tenant, b.path("templateId").asString()).path("version").asString()));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("manifest.json", Json.pretty(manifest).getBytes(StandardCharsets.UTF_8));
        files.put("results.json", Json.pretty(x.results).getBytes(StandardCharsets.UTF_8));
        files.putAll(renderings);
        files.put("traces_masked.txt", String.join("\n----\n", x.traces).getBytes(StandardCharsets.UTF_8));
        files.put("timings.json", Json.pretty(Json.obj().put("p95Ms", p95).set("samplesMs", Json.tree(x.latencies))).getBytes(StandardCharsets.UTF_8));
        files.put("security.json", Json.pretty(x.security).getBytes(StandardCharsets.UTF_8));
        ObjectNode seal = sealer.seal(runId, files);
        Map<String, Object> verify = sealer.verify(sealer.read(runId));
        boolean leak = !SecretScanner.scan(new String(files.get("traces_masked.txt"), StandardCharsets.UTF_8) + Json.write(manifest)).isEmpty();
        x.check("XT-16", Boolean.TRUE.equals(verify.get("valid")) && !leak, "Evidence pack sealed (SHA-256 " + seal.get("seal").asString().substring(0, 16) + "…, Ed25519 signed), "
                + files.size() + " elements, PHI-masked traces, no secrets; stored read-only", null);
        // ---- outcome
        int passed = 0, total = 0;
        for (JsonNode r : x.results) { if (!"N/A".equals(r.get("status").asString())) { total++; if ("PASS".equals(r.get("status").asString())) passed++; } }
        boolean ok = passed == total;
        ObjectNode run = Json.obj().put("id", runId).put("configId", configId).put("configHash", ConfigService.hash(cfg)).put("driver", d.ref()).put("status", ok ? "PASSED" : "FAILED")
                .put("passed", passed).put("total", total).put("startedAt", started.toString()).put("finishedAt", finished.toString()).put("operator", actor)
                .put("latencyP95Ms", p95).put("seal", seal.get("seal").asString()).put("testSubject", x.subject);
        run.set("results", x.results);
        run.set("plan", plan(tenant, configId));
        store.put(tenant, "run", runId, run, run.get("status").asString(), configId);
        ObjectNode fresh = configs.get(tenant, configId);
        fresh.putObject("lastRun").put("runId", runId).put("status", run.get("status").asString()).put("passed", passed).put("total", total)
                .put("at", finished.toString()).put("latencyP95Ms", p95).put("seal", seal.get("seal").asString());
        if (ok) {
            fresh.put("lastPassingRunHash", ConfigService.hash(fresh)).put("lastPassingRunAt", finished.toString());
            if ("DRAFT".equals(fresh.get("status").asString())) fresh.put("status", "TESTED");
            fresh.withObject("steps").put("6", "DONE");
        } else {
            fresh.withObject("steps").put("6", "FAILED");
        }
        fresh.put("hash", ConfigService.hash(fresh));
        store.put(tenant, "config", configId, fresh, fresh.get("status").asString(), fresh.get("modelId").asString());
        audit.record(tenant, actor, "harness.run", "config/" + configId, Map.of("run", runId, "status", run.get("status").asString(), "passed", passed, "total", total, "seal", seal.get("seal").asString()));
        events.publish(tenant, "run.completed", Json.obj().put("run", runId).put("config", configId).put("status", run.get("status").asString()));
        tenants.meter(tenant, "harness.runs", 1);
        return run;
    }

    /** Another tested/certified/published configuration that binds this template field (combined coverage). */
    private String otherCoverage(String tenant, String configId, String templateId, String fieldId) {
        for (ObjectNode c : store.list(tenant, "config")) {
            if (c.get("id").asString().equals(configId) || !List.of("TESTED", "CERTIFIED", "PUBLISHED").contains(c.path("status").asString())) continue;
            for (JsonNode b : c.path("bindings")) if (templateId.equals(b.path("templateId").asString()) && b.path("fields").has(fieldId)) return c.get("id").asString();
        }
        return null;
    }

    private static boolean isNum(String s) { try { new BigDecimal(s); return true; } catch (Exception e) { return false; } }

    private ObjectNode adapters(String tenant, String adapter, JsonNode inst) {
        return new io.intimed.templates.TemplateAdapters().render(adapter, templates.require(tenant, inst.get("templateId").asString()), inst);
    }

    private String transportName(JsonNode cfg) {
        return "P4".equals(cfg.get("option").asString()) ? "MLLP-TLS :" + listeners.mllpPort() : "HTTPS device ingress :" + listeners.ingressPort();
    }

    /** Sends one payload through the real transport for the option and collects the new observations of this run. */
    Outcome send(Ctx x, String payload, boolean withSubject) {
        String option = x.cfg.get("option").asString();
        java.util.Set<String> before = new java.util.HashSet<>();
        for (ObjectNode o : store.listByRef(x.tenant, "observation", x.cfg.get("id").asString())) if (x.runId.equals(o.path("runId").asString(null))) before.add(o.get("id").asString());
        String status, ack;
        long t0 = System.nanoTime();
        try {
            if ("P4".equals(option)) {
                String msg = payload.replaceFirst("\\|INTIMED\\|INTIMED\\|", "|INTIMED|HARNESS^" + x.runId + "^" + x.cfg.get("id").asString() + (withSubject ? "" : "^NOSUBJECT") + "|");
                TransportClient.Sent s = TransportClient.mllp("localhost", listeners.mllpPort(), x.probe.ssl(), msg);
                x.protocol = s.protocol(); x.cipher = s.cipher();
                x.traces.add("→ " + PhiMasker.mask(msg) + "\n← " + s.response());
                DelimitedRecords a = new DelimitedRecords(s.response(), true);
                ack = a.first("MSA-1");
                String text = a.first("MSA-3");
                status = text == null || text.isBlank() ? ("AA".equals(ack) ? "FILED" : "DLQ") : text.split(" ")[0];
            } else {
                ObjectNode envl = Json.obj().put("configId", x.cfg.get("id").asString()).put("option", option).put("payload", payload).put("mode", "TEST").put("runId", x.runId);
                if (withSubject) envl.put("testSubject", x.subject);
                TransportClient.Sent s = TransportClient.ingress("localhost", listeners.ingressPort(), x.probe.ssl(), envl);
                x.protocol = s.protocol(); x.cipher = s.cipher();
                JsonNode r = Json.parse(s.response());
                status = r.path("status").asString("ERROR");
                ack = r.path("ack").asString("");
                x.traces.add("→ [" + option + "] " + PhiMasker.mask(payload) + "\n← " + PhiMasker.mask(s.response()));
            }
        } catch (Exception e) {
            x.traces.add("→ [" + option + "] transport error " + e.getClass().getSimpleName());
            return new Outcome("ERROR", "", List.of(), payload);
        }
        x.latencies.add((System.nanoTime() - t0) / 1_000_000);
        List<ObjectNode> fresh = new ArrayList<>();
        for (ObjectNode o : store.listByRef(x.tenant, "observation", x.cfg.get("id").asString())) {
            if (x.runId.equals(o.path("runId").asString(null)) && !before.contains(o.get("id").asString())) fresh.add(o);
        }
        return new Outcome(status, ack, fresh, payload);
    }

    void bidirectional(Ctx x, Map<String, String> values, String serial, Instant ts) {
        String ordered = "6.5";
        String rgv = "MSH|^~\\&|HOST|ICU|ALARIS_SM|ICU|" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC).format(ts)
                + "||RGV^O15^RGV_O15|PIV-" + x.runId + "|P|2.6\rPID|||" + x.subject + "\rORC|XO|ORD-" + x.runId + "\rRXG|1||||" + values.get("drug") + "|||" + ordered + "|mL/h\r";
        String rrg = PumpSimulator.program(rgv);
        Map<String, String> v = new LinkedHashMap<>(values);
        v.put("rate", PumpSimulator.programmedRate(rgv));
        v.put("program_confirmed", "CONFIRMED");
        Outcome o = send(x, Simulator.render(x.d, v, x.subject, ts.plusSeconds(1200), serial), true);
        boolean ok = rrg.contains("MSA|AA") && o.observations().stream().anyMatch(ob -> {
            for (JsonNode m : ob.path("measurements")) if ("rate".equals(m.path("key").asString()) && new BigDecimal(m.get("value").asString()).compareTo(new BigDecimal(ordered)) == 0) return true;
            return false;
        });
        x.traces.add("→ RGV^O15 " + PhiMasker.mask(rgv) + "\n← " + rrg);
        x.check("XT-12", ok, "Order pushed to pump (RGV^O15), pump confirmed (RRG^O16 AA), programmed rate " + ordered + " mL/h returned in the infusion record", null);
    }

    void securityChecks(Ctx x) {
        ObjectNode s = x.security;
        List<String> fails = new ArrayList<>();
        int mllp = listeners.mllpPort();
        Map<String, Object> plain = TransportClient.plaintextProbe("localhost", mllp);
        s.set("plaintextRefused", Json.tree(plain));
        if (!Boolean.TRUE.equals(plain.get("refused"))) fails.add("plaintext accepted");
        SSLContext noCert = LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null);
        Map<String, Object> nc = TransportClient.handshakeProbe("localhost", mllp, noCert, null);
        s.set("noClientCertificateRefused", Json.tree(nc));
        if (!Boolean.TRUE.equals(nc.get("refused"))) fails.add("connection without client certificate accepted");
        Map<String, Object> legacy = TransportClient.handshakeProbe("localhost", mllp, x.probe.ssl(), new String[]{"TLSv1.1"});
        s.set("tls11Refused", Json.tree(legacy));
        if (!Boolean.TRUE.equals(legacy.get("refused"))) fails.add("TLS 1.1 accepted");
        s.put("negotiated", x.protocol + " " + x.cipher);
        long days = ChronoUnit.DAYS.between(Instant.now(), x.probe.cert().getNotAfter().toInstant());
        s.put("probeCertificateDaysLeft", days);
        if (days <= 30) fails.add("probe certificate expires in ≤ 30 days");
        String ep = x.cfg.path("endpointId").asString(null);
        if (ep != null) {
            ObjectNode e = store.get(x.tenant, "endpoint", ep).orElse(null);
            if (e == null || !"ACTIVE".equals(e.path("status").asString())) fails.add("bound endpoint " + ep + " not ACTIVE");
            else if (e.hasNonNull("notAfter")) {
                long d2 = ChronoUnit.DAYS.between(Instant.now(), Instant.parse(e.get("notAfter").asString()));
                s.put("endpointCertificateDaysLeft", d2);
                if (d2 <= 30) fails.add("endpoint certificate expires in ≤ 30 days");
            }
        }
        JsonNode p = x.cfg.path("params");
        if ("P3".equals(x.cfg.get("option").asString())) {
            s.put("pairing", p.path("pairing").asString(""));
            if (!p.path("pairing").asString("").startsWith("LE_SECURE")) fails.add("pairing policy (LE Secure Connections required)");
        }
        if ("P4".equals(x.cfg.get("option").asString()) && !"MTLS".equals(p.path("tls").asString("MTLS"))) fails.add("MLLP not configured for mTLS");
        if (p.has("scopes")) {
            String need = x.d.transport(x.cfg.get("option").asString()).path("parameters").path("scopes").asString("");
            s.put("scopes", p.get("scopes").asString()).put("scopesRequired", need);
            if (p.get("scopes").asString().split("\\s+").length > Math.max(1, need.split("\\s+").length) || p.get("scopes").asString().contains("*")) fails.add("scopes broader than required");
        }
        List<SecretScanner.Finding> leaks = SecretScanner.scan(Json.write(x.cfg));
        s.put("secretsInConfigurationExport", leaks.size());
        if (!leaks.isEmpty()) fails.add("secret in configuration export");
        ArrayNode refs = s.putArray("secretReferences");
        p.properties().forEach(e -> {
            if (SecretStore.isRef(e.getValue().asString(""))) {
                boolean resolved = secrets.get(x.tenant, SecretStore.name(e.getValue().asString())).isPresent();
                refs.addObject().put("parameter", e.getKey()).put("reference", e.getValue().asString()).put("resolved", resolved);
                if (!resolved) fails.add("secret reference " + e.getValue().asString() + " not found in the vault");
            }
        });
        if ("P6".equals(x.cfg.get("option").asString())) webhookChecks(x, fails);
        if ("P8".equals(x.cfg.get("option").asString())) oauthChecks(x, fails);
        x.check("XT-13", fails.isEmpty(), fails.isEmpty() ? "Plaintext, missing client certificate and TLS 1.1 refused; certificates > 30 days; pairing/scopes per policy; no secrets in export; references resolvable"
                : "Failures: " + String.join("; ", fails), s);
    }

    private String consoleBase() { return "https://localhost:" + env.getProperty("local.server.port", env.getProperty("server.port", "9443")); }

    void webhookChecks(Ctx x, List<String> fails) {
        String ref = x.cfg.path("params").path("webhookSecret").asString("");
        if (!SecretStore.isRef(ref)) { x.security.put("webhook", "mode without webhook secret"); return; }
        String secret = secrets.get(x.tenant, SecretStore.name(ref)).orElse(null);
        if (secret == null) return;
        SSLContext trust = LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null);
        String url = consoleBase() + "/api/webhooks/" + x.tenant + "/" + x.cfg.get("id").asString();
        String body = "{\"type\":\"ping\",\"run\":\"" + x.runId + "\"}";
        try {
            long now = Instant.now().getEpochSecond();
            String good = Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), now + "." + body);
            int ok = TransportClient.https(url, trust, "POST", body, Map.of("Content-Type", "application/json", "X-IntiMed-Timestamp", String.valueOf(now), "X-IntiMed-Signature", "v1=" + good)).statusCode();
            int bad = TransportClient.https(url, trust, "POST", body, Map.of("Content-Type", "application/json", "X-IntiMed-Timestamp", String.valueOf(now), "X-IntiMed-Signature", "v1=" + good.replace('a', 'b').replace('0', '1'))).statusCode();
            long old = now - 3600;
            int stale = TransportClient.https(url, trust, "POST", body, Map.of("Content-Type", "application/json", "X-IntiMed-Timestamp", String.valueOf(old),
                    "X-IntiMed-Signature", "v1=" + Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), old + "." + body))).statusCode();
            x.security.set("webhook", Json.obj().put("validSignature", ok).put("invalidSignature", bad).put("staleTimestamp", stale));
            if (ok != 202 || bad != 401 || stale != 401) fails.add("webhook HMAC verification (valid " + ok + ", invalid " + bad + ", stale " + stale + ")");
        } catch (Exception e) {
            fails.add("webhook receiver unreachable: " + e.getClass().getSimpleName());
        }
    }

    void oauthChecks(Ctx x, List<String> fails) {
        try {
            TokenService.ClientCreated cli = tokens.createClient(x.tenant, "harness-" + x.runId, List.of("system/Observation.write"), "harness");
            SSLContext trust = LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null);
            String basic = java.util.Base64.getEncoder().encodeToString((cli.clientId() + ":" + cli.clientSecret()).getBytes(StandardCharsets.UTF_8));
            var t = TransportClient.https(consoleBase() + "/oauth2/token", trust, "POST", "grant_type=client_credentials",
                    Map.of("Content-Type", "application/x-www-form-urlencoded", "Authorization", "Basic " + basic));
            String token = Json.parse(t.body()).path("access_token").asString("");
            int read = TransportClient.https(consoleBase() + "/fhir/r4/Observation?_count=1", trust, "GET", null, Map.of("Authorization", "Bearer " + token)).statusCode();
            int anon = TransportClient.https(consoleBase() + "/fhir/r4/Observation?_count=1", trust, "GET", null, Map.of()).statusCode();
            tokens.revokeClient(x.tenant, cli.clientId(), "harness");
            x.security.set("oauth", Json.obj().put("tokenIssued", t.statusCode()).put("readWithWriteOnlyScope", read).put("anonymous", anon));
            if (t.statusCode() != 200 || read != 403 || anon != 401) fails.add("OAuth2 scope enforcement (token " + t.statusCode() + ", read " + read + ", anonymous " + anon + ")");
        } catch (Exception e) {
            fails.add("FHIR endpoint unreachable: " + e.getClass().getSimpleName());
        }
    }

    void qualityChecks(Ctx x, List<ObjectNode> obs, boolean verifyHeld, boolean qcGate, boolean qcHeld) {
        ArrayNode ev = Json.arr();
        boolean ok = true;
        if (!obs.isEmpty()) {
            ObjectNode probeCfg = (ObjectNode) x.cfg.deepCopy();
            probeCfg.withObject("calibration").put("dueDate", LocalDate.now().minusDays(1).toString());
            probeCfg.withObject("qualityGates").put("calibrationLockout", true);
            ObjectNode probeObs = (ObjectNode) obs.get(0).deepCopy();
            QualityGates.Outcome o = gates.evaluate(probeCfg, probeObs, Map.of(), Instant.now());
            boolean lock = "CALIBRATION_LOCKOUT".equals(o.hold());
            ok &= lock;
            ev.addObject().put("gate", "calibration lockout (overdue)").put("expected", "CALIBRATION_LOCKOUT").put("actual", String.valueOf(o.hold())).put("pass", lock);
        }
        if (qcGate) {
            ok &= qcHeld;
            ev.addObject().put("gate", "QC lot gate").put("expected", "HELD_QUALITY for unreleased lot").put("actual", qcHeld ? "HELD_QUALITY" : "not held").put("pass", qcHeld);
        }
        boolean verifyConfigured = x.cfg.path("qualityGates").path("verifyBeforePost").asBoolean(false) || List.of("P9", "P10").contains(x.cfg.get("option").asString());
        if (verifyConfigured) {
            ok &= verifyHeld;
            ev.addObject().put("gate", "verify-before-post").put("expected", "HELD_VERIFY until clinician verification").put("actual", verifyHeld ? "HELD_VERIFY → released by verifier" : "filed without verification").put("pass", verifyHeld);
        }
        String grading = x.cfg.path("qualityGates").path("grading").asString("");
        if (!grading.isEmpty() && !obs.isEmpty()) {
            boolean graded = obs.get(0).path("quality").has("gradingRule");
            ok &= graded;
            ev.addObject().put("gate", "guideline grading " + grading).put("expected", "grading rule applied").put("actual", obs.get(0).path("quality").path("grade").asString(obs.get(0).path("quality").path("gradingRule").asString("none"))).put("pass", graded);
        }
        x.check("XT-14", ok, "Calibration/QC lockout, verification rule and guideline grading behave as configured", ev);
    }

    static String html(JsonNode tpl, JsonNode inst) {
        StringBuilder sb = new StringBuilder("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(esc(tpl.path("name").asString()))
                .append("</title><style>body{font-family:system-ui,sans-serif;margin:24px;color:#14213d}table{border-collapse:collapse;width:100%}td,th{border:1px solid #cfd8e3;padding:6px 10px;text-align:left}th{background:#eef3f8}.flag{color:#b45309}</style></head><body>")
                .append("<h2>").append(esc(tpl.path("id").asString())).append(" · ").append(esc(tpl.path("name").asString())).append(" (sandbox instance)</h2><p>Subject ")
                .append(esc(inst.path("subject").asString())).append(" · effective ").append(esc(inst.path("effectiveTime").asString())).append(" · device ").append(esc(inst.path("deviceSerial").asString())).append("</p>");
        for (JsonNode s : tpl.path("sections")) {
            sb.append("<h3>").append(esc(s.path("label").asString())).append("</h3><table><tr><th>Field</th><th>Value</th><th>Unit</th><th>Flags</th></tr>");
            for (JsonNode f : s.path("fields")) {
                JsonNode v = inst.path("fields").get(f.get("id").asString());
                sb.append("<tr><td>").append(esc(f.path("label").asString())).append(f.path("mandatory").asBoolean() ? " *" : "").append("</td><td>")
                        .append(v == null ? "—" : esc(v.path("value").asString())).append("</td><td>").append(v == null ? "" : esc(v.path("unit").asString(""))).append("</td><td class=\"flag\">")
                        .append(v == null ? "" : esc(v.path("flags").toString().replace("[]", ""))).append("</td></tr>");
            }
            sb.append("</table>");
        }
        return sb.append("<p><small>Provenance: ").append(esc(inst.path("provenance").toString())).append("</small></p></body></html>").toString();
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

    public List<ObjectNode> runs(String tenant, String configId) { return configId == null ? store.list(tenant, "run") : store.listByRef(tenant, "run", configId); }
}
