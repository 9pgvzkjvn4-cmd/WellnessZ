package io.intimed.demo;

import io.intimed.capture.CaptureService;
import io.intimed.catalogue.CatalogueService;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.sim.Simulator;
import io.intimed.harness.HarnessService;
import io.intimed.harness.ProbeIdentity;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.UserService;
import io.intimed.security.secrets.SecretStore;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.Bootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo pack scenario runner (demo profile only): one end-to-end scenario per reference template TPL-01…TPL-22 – the
 * provisioning wizard with recommendations, the exchange test with sealed evidence, four-eyes (+ security co-approval
 * where the vendor posture needs an exception), publish DEV → UAT → PROD (with SRE approval) and a live reading over
 * mTLS from the demo gateway into the host template. Every step runs through the same services and transports as the
 * console; personas act under their own identity so the audit trail reads like a real day.
 */
@Service
@Order(10)
public class DemoService implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(DemoService.class);
    public static final String TENANT = "acme-health";
    /** template → primary driver, site, test subject. */
    public static final Map<String, String[]> SCENARIOS = new LinkedHashMap<>();
    static {
        String[][] s = {
            {"TPL-01", "monitor.philips.intellivue-pcd01@1.0.0", "ICU", "ICU-ST1", "BED-07", "MRN-100231"},
            {"TPL-02", "vitals.welchallyn.csm-pcd01@1.0.0", "Emergency department", "ED-TRIAGE-1", "", "MRN-100232"},
            {"TPL-03", "anaesthesia.ge.aisys-pcd01@1.0.0", "Theatres", "OR-3", "", "MRN-100233"},
            {"TPL-04", "infusion.bd.alaris-ipec@1.0.0", "ICU", "ICU-ST2", "BED-08", "MRN-100234"},
            {"TPL-05", "bloodgas.radiometer.abl90-poct1a@1.0.0", "ICU", "ICU-BG", "", "MRN-100235"},
            {"TPL-06", "lab.roche.cobas-astm@1.0.0", "Core laboratory", "LAB-COBAS", "", "MRN-100236"},
            {"TPL-07", "imaging.ct.dicom-rdsr@1.0.0", "Radiology", "CT-1", "", "MRN-100237"},
            {"TPL-08", "ecg.ge.muse-xml@1.0.0", "Cardiology", "ECG-1", "", "MRN-100238"},
            {"TPL-09", "idco.medtronic.carelink-hl7@1.0.0", "Device clinic", "DC-REMOTE", "", "MRN-100239"},
            {"TPL-10", "dialysis.fresenius.5008-tdms@1.0.0", "Dialysis unit", "HD-04", "", "MRN-100240"},
            {"TPL-11", "neonatal.drager.babyleo-serial@1.0.0", "NICU", "NICU-ST1", "COT-03", "MRN-100241"},
            {"TPL-12", "fetal.philips.avalon-hl7@1.0.0", "Labour ward", "LW-2", "LW-BED-2", "MRN-100242"},
            {"TPL-13", "rpm.bodytrace.cloud-webhook@1.0.0", "RPM programme", "HOME", "", "MRN-100243"},
            {"TPL-14", "hah.masimo.safetynet-cloud@1.0.0", "Hospital at home", "HOME", "", "MRN-100244"},
            {"TPL-15", "sleep.natus.embla-csv@1.0.0", "Sleep lab", "PSG-1", "", "MRN-100245"},
            {"TPL-16", "perfusion.terumo.system1-json@1.0.0", "Theatres", "OR-CARDIAC", "", "MRN-100246"},
            {"TPL-17", "audio.benson.cca200-xml@1.0.0", "OH clinic", "OH-AUDIO-1", "", "EMP-700101"},
            {"TPL-18", "spiro.ndd.easyone-gdt@1.0.0", "OH clinic", "OH-SPIRO-1", "", "EMP-700102"},
            {"TPL-19", "vision.optec.6500-ocr@1.0.0", "OH clinic", "OH-VISION-1", "", "EMP-700103"},
            {"TPL-20", "da.lifeloc.fc20-serial@1.0.0", "OH clinic", "OH-DA-1", "", "EMP-700104"},
            {"TPL-21", "fit.tsi.portacount-cloud@1.0.0", "OH clinic", "OH-FIT-1", "", "EMP-700105"},
            {"TPL-22", "wear.aggregator.fhir-pcha@1.0.0", "Wellness programme", "HOME", "", "EMP-700106"}};
        for (String[] r : s) SCENARIOS.put(r[0], new String[]{r[1], r[2], r[3], r[4], r[5]});
    }
    /** Synthetic demographics for reference-interval partitioning (birth date relative to today, sex, pregnancy). */
    static ObjectNode demographics(String tpl, ObjectNode sub) {
        java.time.LocalDate today = java.time.LocalDate.now();
        int n = Integer.parseInt(tpl.substring(4));
        switch (tpl) {
            case "TPL-11" -> sub.put("birthDate", today.minusDays(3).toString()).put("sex", "female");
            case "TPL-12" -> sub.put("birthDate", today.minusYears(29).toString()).put("sex", "female").put("pregnant", true).put("trimester", 3);
            case "TPL-15" -> sub.put("birthDate", today.minusYears(52).toString()).put("sex", "male");
            default -> sub.put("birthDate", today.minusYears(30 + (n * 7) % 45).minusDays(n * 11L).toString()).put("sex", n % 2 == 0 ? "female" : "male");
        }
        return sub;
    }

    /** Left un-provisioned at start so the guided walkthrough provisions them live. */
    public static final List<String> GUIDED = List.of("TPL-18", "TPL-02");

    private final ConfigService configs;
    private final HarnessService harness;
    private final DriverRegistry drivers;
    private final CatalogueService catalogue;
    private final AssociationEngine association;
    private final CaptureService capture;
    private final DocumentStore store;
    private final UserService users;
    private final SecretStore secrets;
    private final ProbeIdentity probes;
    private final EndpointService endpoints;
    private final boolean demo;
    private final boolean autoprovision;
    private volatile boolean streaming;
    private volatile String progress = "idle";

    public DemoService(ConfigService configs, HarnessService harness, DriverRegistry drivers, CatalogueService catalogue, AssociationEngine association, CaptureService capture,
                       DocumentStore store, UserService users, SecretStore secrets, ProbeIdentity probes, EndpointService endpoints,
                       @Value("${intimed.demo.enabled:false}") boolean demo, @Value("${intimed.demo.autoprovision:true}") boolean autoprovision) {
        this.configs = configs;
        this.harness = harness;
        this.drivers = drivers;
        this.catalogue = catalogue;
        this.association = association;
        this.capture = capture;
        this.store = store;
        this.users = users;
        this.secrets = secrets;
        this.probes = probes;
        this.endpoints = endpoints;
        this.demo = demo;
        this.autoprovision = autoprovision;
    }

    public boolean enabled() { return demo; }

    private IntiPrincipal as(String username) { return users.find(username).principal(); }

    @Override
    public void run(ApplicationArguments args) {
        if (!demo || store.get(TENANT, "demo", "seed").isPresent()) return;
        try { seed(); } catch (RuntimeException e) { LOG.error("demo seed failed: {}", e.toString()); return; }
        if (autoprovision) Thread.ofVirtual().name("demo-autoprovision").start(() -> {
            try { runAll(false); } catch (RuntimeException e) { LOG.warn("demo autoprovision failed: {}", e.getMessage()); }
        });
    }

    void seed() {
        Current.as(as("nurse.clinic"), () -> {
            for (var en : SCENARIOS.entrySet()) {
                String[] s = en.getValue();
                ObjectNode sub = demographics(en.getKey(), Json.obj().put("id", s[4]).put("mrn", s[4]).put("name", "Synthetic subject " + s[4].substring(s[4].length() - 3)).put("badge", "BADGE-" + s[4].substring(s[4].length() - 3)));
                sub.set("vendorIds", Json.arr().add(s[4].replace("-", "")));
                store.put(TENANT, "subject", s[4], sub, "ACTIVE", null);
            }
            store.put(TENANT, "subject", "MRN-100299", Json.obj().put("id", "MRN-100299").put("mrn", "MRN-100299").put("name", "Synthetic subject 299").put("badge", "BADGE-299")
                    .put("birthDate", java.time.LocalDate.now().minusYears(8).toString()).put("sex", "male")
                    .set("vendorIds", Json.arr().add("100299")), "ACTIVE", null);
            return null;
        });
        Current.as(as("soc.analyst"), () -> {
            for (String n : List.of("benson-share", "bodytrace-webhook", "safetynet-client", "tsi-link-webhook")) secrets.put(TENANT, n, "demo-" + n + "-" + io.intimed.common.Hashing.randomToken(12), "user:soc.analyst");
            for (String[] s : SCENARIOS.values()) {
                DriverPackage d = drivers.require(TENANT, s[0]);
                String model = d.models().get(0);
                boolean exception = "IMD-015".equals(model);
                catalogue.recordPosture(TENANT, model, Json.obj().put("mds2", "RECEIVED").put("sbom", exception ? "MISSING" : "RECEIVED").put("knownVulnerabilities", 0), "user:soc.analyst");
            }
            return null;
        });
        probes.demoGateway(TENANT);
        Current.as(as("admin.device"), () -> endpoints.register(TENANT, "ICU vendor gateway (awaiting enrolment)", "GATEWAY", "ICU", "MDZ-ICU", "user:admin.device"));
        store.put(TENANT, "demo", "seed", Json.obj().put("at", Instant.now().toString()), "DONE", null);
    }

    public Map<String, Object> status() {
        ArrayNode rows = Json.arr();
        for (var e : SCENARIOS.entrySet()) {
            ObjectNode r = store.get(TENANT, "demo-result", e.getKey()).orElse(Json.obj().put("template", e.getKey()).put("status", "NOT_RUN"));
            r.put("driver", e.getValue()[0]);
            rows.add(r);
        }
        return Map.of("progress", progress, "streaming", streaming, "scenarios", rows);
    }

    public synchronized Map<String, Object> runAll(boolean includeGuided) {
        int n = 0;
        for (String tpl : SCENARIOS.keySet()) {
            if (!includeGuided && GUIDED.contains(tpl)) continue;
            progress = "running " + tpl;
            try { runScenario(tpl); } catch (RuntimeException e) {
                store.put(TENANT, "demo-result", tpl, Json.obj().put("template", tpl).put("status", "ERROR").put("error", String.valueOf(e.getMessage())), "ERROR", null);
            }
            n++;
        }
        progress = "completed " + n + " scenarios at " + Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return status();
    }

    public ObjectNode runScenario(String tpl) {
        String[] s = SCENARIOS.get(tpl);
        if (s == null) throw ApiException.notFound("scenario " + tpl);
        DriverPackage d = drivers.require(TENANT, s[0]);
        String model = d.models().get(0);
        ObjectNode existing = store.list(TENANT, "config").stream().filter(c -> s[0].equals(c.path("driver").asString()) && "PUBLISHED".equals(c.path("status").asString())).findFirst().orElse(null);
        ObjectNode out = Json.obj().put("template", tpl).put("driver", s[0]).put("model", model);
        String id;
        if (existing != null) {
            id = existing.get("id").asString();
            JsonNode lr = existing.path("lastRun");
            if (lr.has("runId")) out.put("run", lr.get("runId").asString()).put("runStatus", lr.path("status").asString())
                    .put("checks", lr.path("passed").asInt() + "/" + lr.path("total").asInt()).put("seal", lr.path("seal").asString());
        } else {
            ObjectNode site = Json.obj().put("site", s[1]).put("station", s[2]);
            if (!s[3].isEmpty()) site.put("bed", s[3]);
            if ("HOME".equals(s[2])) site.put("setting", "HOME");
            id = Current.as(as("admin.device"), () -> {
                ObjectNode c = configs.create(TENANT, tpl + " · " + d.name().split(" – ")[0], model, site, "user:admin.device");
                String cid = c.get("id").asString();
                configs.saveStep(TENANT, cid, 1, Json.obj().put("driver", s[0]), "user:admin.device");
                for (int step = 2; step <= 5; step++) {
                    configs.recommendations(TENANT, cid, step);
                    configs.acceptAll(TENANT, cid, step, "user:admin.device");
                    ObjectNode body = Json.obj();
                    if (step == 3) body.put("endpointId", probes.demoGateway(TENANT).endpointId());
                    if (step == 5) body.set("expectedSerials", Json.arr().add(d.tests().path("serial").asString()));
                    configs.saveStep(TENANT, cid, step, body, "user:admin.device");
                }
                configs.recommendations(TENANT, cid, 6);
                configs.acceptAll(TENANT, cid, 6, "user:admin.device");
                configs.saveStep(TENANT, cid, 6, Json.obj(), "user:admin.device");
                return cid;
            });
            ObjectNode run = Current.as(as("admin.device"), () -> harness.run(TENANT, id, "user:admin.device"));
            out.put("run", run.get("id").asString()).put("runStatus", run.get("status").asString()).put("checks", run.get("passed").asInt() + "/" + run.get("total").asInt())
                    .put("seal", run.get("seal").asString());
            if (!"PASSED".equals(run.get("status").asString())) {
                out.put("status", "TEST_FAILED");
                ArrayNode failed = out.putArray("failedChecks");
                run.get("results").forEach(r -> { if ("FAIL".equals(r.get("status").asString())) failed.add(r.get("id").asString() + ": " + r.get("detail").asString()); });
                store.put(TENANT, "demo-result", tpl, out, "TEST_FAILED", null);
                return out;
            }
            boolean needsException = !"ACCEPTABLE".equals(catalogue.posture(TENANT, model).path("status").asString());
            Current.as(as("admin.device"), () -> {
                configs.recommendations(TENANT, id, 7);
                if (needsException) configs.requestPostureException(TENANT, id, "Vendor SBOM requested (ticket VEN-2026-117); device isolated on the OH VLAN; read-only export share", "user:admin.device");
                return configs.submitForCertification(TENANT, id, "user:admin.device");
            });
            Current.as(as("approver.device"), () -> configs.approve(TENANT, id, "Evidence reviewed – exchange test passed", "user:approver.device"));
            if (needsException) Current.as(as("soc.analyst"), () -> configs.approve(TENANT, id, "Posture exception accepted with isolation", "user:soc.analyst"));
            Current.as(as("admin.device"), () -> configs.publish(TENANT, id, "DEV", "user:admin.device"));
            Current.as(as("admin.device"), () -> configs.publish(TENANT, id, "UAT", "user:admin.device"));
            Current.as(as("sre"), () -> configs.approvePromotion(TENANT, id, "PROD", "user:sre"));
            Current.as(as("admin.device"), () -> configs.publish(TENANT, id, "PROD", "user:admin.device"));
        }
        ObjectNode cfg = configs.get(TENANT, id);
        out.put("configId", id).put("stage", cfg.path("stage").asString()).put("certification", cfg.path("certificationId").asString(""));
        // live reading through the demo gateway (mTLS) into the host template
        ObjectNode live = Current.as(as("nurse.clinic"), () -> liveReading(cfg, d, s[4]));
        out.set("live", live);
        out.put("status", "PUBLISHED_AND_LIVE");
        store.put(TENANT, "demo-result", tpl, out, "DONE", null);
        return out;
    }

    ObjectNode liveReading(ObjectNode cfg, DriverPackage d, String subject) {
        String method = cfg.at("/association/method").asString();
        String station = cfg.at("/site/station").asString();
        String tplId = cfg.path("bindings").path(0).path("templateId").asString();
        switch (method) {
            case "ENCOUNTER_BED" -> association.admit(TENANT, cfg.at("/site/bed").asString(), subject, "ENC-" + subject.substring(subject.length() - 3));
            case "WORKLIST" -> association.openWorklist(TENANT, station, subject, tplId, "ORD-" + subject, 240);
            case "SESSION" -> association.openSession(TENANT, station, subject, tplId, "nurse.clinic", 15);
            case "CONSENT" -> {
                boolean enrolled = store.list(TENANT, "enrolment").stream().anyMatch(e -> d.tests().path("serial").asString().equals(e.path("deviceSerial").asString()) && "ACTIVE".equals(e.path("status").asString()));
                List<String> scopes = List.of(cfg.at("/params/scopes").asString("readings").split(" "));
                if (!enrolled) association.enrol(TENANT, d.tests().path("serial").asString(), subject, cfg.get("id").asString(), scopes, 90);
            }
            default -> { }
        }
        String serial = cfg.path("expectedSerials").path(0).asString(d.tests().path("serial").asString());
        String payload = Simulator.render(d, Simulator.vary(Simulator.values(d), System.nanoTime()), "MATCH".equals(method) ? subject : subject, Instant.now().truncatedTo(ChronoUnit.SECONDS), serial);
        ObjectNode r;
        if (List.of("P9", "P10").contains(cfg.path("option").asString())) {
            r = capture.submitForm(TENANT, cfg.get("id").asString(), Json.parse(payload), "nurse.clinic");
        } else {
            r = capture.sendLive(TENANT, cfg, payload);
        }
        if ("HELD_QUALITY".equals(r.path("status").asString()) && cfg.path("qualityGates").path("qcLotGate").asBoolean(false)) {
            // QC gate held the result from a new reagent lot: the nurse records the lot's QC, the next result files
            String lot = d.tests().path("values").path("lot").asString();
            configs.recordQc(TENANT, cfg.get("id").asString(), lot, true, "user:nurse.clinic");
            ObjectNode fresh = configs.get(TENANT, cfg.get("id").asString());
            r = capture.sendLive(TENANT, fresh, Simulator.render(d, Simulator.vary(Simulator.values(d), 7), subject, Instant.now().plusSeconds(5).truncatedTo(ChronoUnit.SECONDS), serial));
            r.put("qcRecordedForLot", lot);
        }
        // clinician verification for held readings (OCR, manual, out-of-range)
        for (ObjectNode o : store.listByRef(TENANT, "observation", cfg.get("id").asString())) {
            if ("HELD_VERIFY".equals(o.path("status").asString()) && "LIVE".equals(o.path("mode").asString())) {
                capture.accept(TENANT, o.get("id").asString(), null, "nurse.clinic");
                r.put("verifiedByClinician", true);
            }
        }
        long inst = store.listByRef(TENANT, "template-instance", tplId).stream().filter(i -> subject.equals(i.path("subject").asString())).count();
        r.put("templateInstances", inst).put("subjectRef", "sha256:" + io.intimed.common.Hashing.sha256(subject).substring(0, 12)).put("association", method); // no clear-text identifier at rest (SC-10)
        return r;
    }

    /**
     * Drift drill (IM-E15): the hospital-at-home oximeter probe "drifts" – six live readings with SpO2 in the 80s and a
     * raised pulse are filed through the real pipeline (each flagged L/LL/H against the subject's reference interval),
     * then the drift monitor raises an AN-08 data-quality alert for the biomedical team.
     */
    public ObjectNode driftDrill(io.intimed.intelligence.DriftMonitor drift) {
        String driver = SCENARIOS.get("TPL-14")[0], subject = SCENARIOS.get("TPL-14")[4];
        ObjectNode cfg = store.listByStatus(TENANT, "config", "PUBLISHED").stream().filter(c -> driver.equals(c.path("driver").asString())).findFirst()
                .orElseThrow(() -> io.intimed.common.ApiException.conflict("not-provisioned", "provision the TPL-14 hospital-at-home scenario first"));
        DriverPackage d = drivers.require(TENANT, driver);
        ArrayNode sent = Json.arr();
        Current.as(as("nurse.icu"), () -> {
            for (int i = 0; i < 6; i++) {
                java.util.Map<String, String> v = new java.util.LinkedHashMap<>(Simulator.values(d));
                v.put("spo2", String.valueOf(84 + i % 3));
                v.put("hr", String.valueOf(112 + i));
                String serial = cfg.path("expectedSerials").path(0).asString(d.tests().path("serial").asString());
                ObjectNode r = capture.sendLive(TENANT, cfg, Simulator.render(d, v, subject, Instant.now().plusSeconds(i * 60L).truncatedTo(ChronoUnit.SECONDS), serial));
                sent.add(r.path("status").asString());
            }
            return null;
        });
        ObjectNode out = Current.as(as("admin.device"), () -> drift.analyse(TENANT, "user:admin.device"));
        out.set("sent", sent);
        return out;
    }

    // ---------------------------------------------------------------- live streaming of continuous devices (demo dashboards)
    public void setStreaming(boolean on) { this.streaming = on; }

    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void stream() {
        if (!demo || !streaming) return;
        Current.as(as("nurse.icu"), () -> {
            for (ObjectNode c : store.listByStatus(TENANT, "config", "PUBLISHED")) {
                if (!"CONTINUOUS".equals(c.path("captureMode").asString())) continue;
                DriverPackage d = drivers.require(TENANT, c.get("driver").asString());
                String subject = SCENARIOS.values().stream().filter(s -> s[0].equals(d.ref())).map(s -> s[4]).findFirst().orElse("MRN-100299");
                try {
                    capture.sendLive(TENANT, c, Simulator.render(d, Simulator.vary(Simulator.values(d), System.nanoTime()), subject, Instant.now().truncatedTo(ChronoUnit.SECONDS),
                            c.path("expectedSerials").path(0).asString(d.tests().path("serial").asString())));
                } catch (RuntimeException e) { LOG.debug("stream {}: {}", c.get("id").asString(), e.getMessage()); }
            }
            return null;
        });
    }

    public ObjectNode anomalyDrill(String configId) {
        ObjectNode c = configs.get(TENANT, configId);
        DriverPackage d = drivers.require(TENANT, c.get("driver").asString());
        int sent = 0;
        ObjectNode last = Json.obj();
        for (int i = 0; i < 35; i++) {
            try {
                last = capture.sendLive(TENANT, c, Simulator.render(d, Simulator.vary(Simulator.values(d), i), "MRN-100299", Instant.now().plusSeconds(i), c.path("expectedSerials").path(0).asString(d.tests().path("serial").asString())));
                sent++;
            } catch (RuntimeException e) { last = Json.obj().put("refused", e.getMessage()); break; }
        }
        return Json.obj().put("sent", sent).set("last", last);
    }

    public JsonNode personas() {
        ArrayNode a = Json.arr();
        for (Bootstrap.Persona p : Bootstrap.PERSONAS) a.addObject().put("tenant", p.tenant()).put("username", p.username()).put("name", p.name()).put("roles", p.roles().toString());
        return a;
    }
}
