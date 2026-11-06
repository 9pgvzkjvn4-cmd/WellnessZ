package io.intimed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.intimed.api.FhirController;
import io.intimed.api.WebhookService;
import io.intimed.clinical.IntervalGovernance;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.engine.association.HostDemographics;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.transport.DeviceListeners;
import io.intimed.engine.transport.TransportClient;
import io.intimed.events.EventBus;
import io.intimed.harness.HarnessService;
import io.intimed.harness.ProbeIdentity;
import io.intimed.intelligence.CohortSimulator;
import io.intimed.intelligence.DriftMonitor;
import io.intimed.intelligence.FhirTerminologyClient;
import io.intimed.intelligence.TerminologyService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.UserService;
import io.intimed.security.pki.LocalPki;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import io.intimed.tenancy.TenantService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IntiMed 2.1 – deferred clinical-intelligence items end to end on the running platform (same Spring context as
 * {@link PlatformIntegrationTest}, distinct tenants): host demographics over MLLP ADT and FHIR Patient (IM-E11-BL-006),
 * delta checks + observation.flagged webhook event (IM-E14-BL-012), derived values (IM-E14-BL-010), tenant interval
 * governance with EP28 and four-eyes (IM-E14-BL-011) used in live annotation and XT-17, RR-17/RR-18 (IM-E12-BL-009), the
 * terminology adapter against a local HTTPS stub (IM-E12-BL-010), drift acknowledge/resolve (IM-E15-BL-003) and
 * trajectories (IM-E13-BL-005).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "intimed.device.mllp-port=0", "intimed.device.ingress-port=0", "management.server.port=0", "intimed.demo.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClinicalIntelligenceDeferredIntegrationTest {
    static final String T = "t-clin21", D = "t-drift21", PW = "Integration-Test-2026";
    static final String ABL = "bloodgas.radiometer.abl90-poct1a@1.0.0", COBAS = "lab.roche.cobas-astm@1.0.0", SPIRO = "spiro.ndd.easyone-gdt@1.0.0";
    static final String SUBJECT = "SUBJ-ADT-1";
    static HttpsServer stub;
    static final AtomicInteger stubCalls = new AtomicInteger();

    static {
        try {
            Path home = Files.createTempDirectory("intimed-it21");
            System.setProperty("intimed.home", home.toString());
            System.setProperty("spring.datasource.url", "jdbc:h2:file:" + home + "/db/intimed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CIPHER=AES");
            IntiMedApplication.prepareTls();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired TenantService tenants;
    @Autowired UserService users;
    @Autowired DocumentStore store;
    @Autowired DriverRegistry drivers;
    @Autowired Pipeline pipeline;
    @Autowired DeviceListeners listeners;
    @Autowired ProbeIdentity probes;
    @Autowired HostDemographics demographics;
    @Autowired FhirController fhir;
    @Autowired WebhookService webhooks;
    @Autowired EventBus events;
    @Autowired IntervalGovernance intervals;
    @Autowired ConfigService configs;
    @Autowired HarnessService harness;
    @Autowired TemplateRegistry templates;
    @Autowired FhirTerminologyClient terminology;
    @Autowired DriftMonitor drift;
    @Autowired CohortSimulator cohorts;
    @Autowired AuditService audit;
    @Autowired io.intimed.engine.association.AssociationEngine association;

    IntiPrincipal admin, approver, nurse, reg1, reg2, owner, client;
    String ablCfg, cobasCfg, spiroCfg;

    @BeforeAll
    void setup() throws Exception {
        tenants.create(T, "Clinical 2.1", "eu-west", "ENTERPRISE", null, "test");
        tenants.create(D, "Drift 2.1", "eu-west", "ENTERPRISE", null, "test");
        admin = users.create(T, "c21.admin", PW, "Admin", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        approver = users.create(T, "c21.approver", PW, "Approver", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        nurse = users.create(T, "c21.nurse", PW, "Nurse", EnumSet.of(Role.CLINICIAN), "test").principal();
        reg1 = users.create(T, "c21.reg1", PW, "Regulatory 1", EnumSet.of(Role.REGULATORY), "test").principal();
        reg2 = users.create(T, "c21.reg2", PW, "Lab physician", EnumSet.of(Role.PHYSICIAN), "test").principal();
        owner = users.create(T, "c21.owner", PW, "Template owner", EnumSet.of(Role.TEMPLATE_OWNER), "test").principal();
        client = new IntiPrincipal("his-client", "his-client", T, "API client his-client", EnumSet.noneOf(Role.class), Set.of("system/Patient.write"), true);
        IntiPrincipal soc = users.create(T, "c21.soc", PW, "SOC", EnumSet.of(Role.SECURITY_ANALYST), "test").principal();
        Current.as(soc, () -> {
            new io.intimed.catalogue.CatalogueService(drivers, store, audit).recordPosture(T, "IMD-001",
                    Json.obj().put("mds2", "RECEIVED").put("sbom", "RECEIVED").put("knownVulnerabilities", 0), "user:c21.soc");
            return null;
        });
        ablCfg = liveConfig("cfg-c21-abl", ABL, Json.obj());
        cobasCfg = liveConfig("cfg-c21-cobas", COBAS, Json.obj().put("derivedValues", true));
        // the analyser sends sample ids only: worklist association at the station (order from the host)
        ObjectNode cobas = store.require(T, "config", cobasCfg);
        cobas.putObject("association").put("method", "WORKLIST").put("threshold", 0.95);
        cobas.putObject("site").put("station", "C21-LAB");
        store.put(T, "config", cobasCfg, cobas, "PUBLISHED", cobas.path("modelId").asString());
        // local HTTPS terminology server stub (no internet): $lookup, $validate-code, $translate
        stub = HttpsServer.create(new InetSocketAddress("localhost", 0), 10);
        stub.setHttpsConfigurator(new HttpsConfigurator(LocalPki.get().serverSslContext()));
        stub.createContext("/fhir/", this::terminologyStub);
        stub.start();
        terminology.configure(true, "https://localhost:" + stub.getAddress().getPort() + "/fhir", "localhost", 2000, 60, true);
    }

    @AfterAll
    void teardown() {
        terminology.configure(false, "", "", 3000, 60, false);
        if (stub != null) stub.stop(0);
    }

    private void terminologyStub(HttpExchange ex) throws java.io.IOException {
        stubCalls.incrementAndGet();
        String path = ex.getRequestURI().getPath(), q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String body;
        boolean tiff = q.contains("code=TIFF");
        if (path.endsWith("/ConceptMap/$translate")) {
            body = tiff ? "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":true},{\"name\":\"match\",\"part\":[{\"name\":\"equivalence\",\"valueCode\":\"equivalent\"},"
                    + "{\"name\":\"concept\",\"valueCoding\":{\"system\":\"http://loinc.org\",\"code\":\"19926-5\",\"display\":\"FEV1/FVC\"}}]}]}"
                    : "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":false}]}";
        } else if (path.endsWith("/CodeSystem/$lookup")) {
            body = tiff ? "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"name\",\"valueString\":\"Hospital codes\"},{\"name\":\"display\",\"valueString\":\"FEV1/FVC (Tiffeneau-Pinelli index)\"}]}"
                    : "{\"resourceType\":\"OperationOutcome\"}";
        } else if (path.endsWith("/CodeSystem/$validate-code")) {
            body = "{\"resourceType\":\"Parameters\",\"parameter\":[{\"name\":\"result\",\"valueBoolean\":" + tiff + "}]}";
        } else {
            body = "{\"resourceType\":\"OperationOutcome\"}";
        }
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/fhir+json");
        ex.sendResponseHeaders(body.contains("OperationOutcome") ? 404 : 200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    /** A published live configuration (association MATCH against the subject registry) for pipeline-level tests. */
    String liveConfig(String id, String driver, ObjectNode params) {
        DriverPackage d = drivers.require(T, driver);
        ObjectNode c = Json.obj().put("id", id).put("name", id).put("driver", driver).put("option", d.options().get(0)).put("status", "PUBLISHED")
                .put("captureMode", "SPOT").put("modelId", d.models().isEmpty() ? "IMD-000" : d.models().get(0)).put("hash", "h-" + id);
        c.putObject("association").put("method", "MATCH").put("threshold", 0.95);
        c.set("params", params);
        ArrayNode bs = c.putArray("bindings");
        d.bindings().properties().forEach(e -> bs.addObject().put("templateId", e.getKey()).set("fields", e.getValue().get("fields")));
        store.put(T, "config", id, c, "PUBLISHED", c.get("modelId").asString());
        return id;
    }

    PipelineResult live(String cfgId, String driver, Map<String, String> overrides, Instant at) {
        DriverPackage d = drivers.require(T, driver);
        Map<String, String> v = Simulator.values(d);
        v.putAll(overrides);
        String payload = Simulator.render(d, v, SUBJECT, at, d.tests().path("serial").asString("SIM-1"));
        return pipeline.process(Envelope.live(T, null, cfgId, d.options().get(0), payload, "it"));
    }

    ObjectNode instanceOf(PipelineResult r) { return store.require(T, "template-instance", r.instanceIds.get(0)); }

    static JsonNode fieldBySource(ObjectNode inst, String key) {
        for (var f : inst.path("fields").properties()) if (key.equals(f.getValue().path("sourceKey").asString())) return f.getValue();
        return null;
    }

    static String adt(String event, String ctrl, String ts, String birth, String sex) {
        return String.join("\r", "MSH|^~\\&|HIS|HOSP|INTIMED|ICU|" + ts + "||ADT^" + event + "|" + ctrl + "|P|2.5", "EVN|" + event + "|" + ts,
                "PID|1||" + SUBJECT + "^^^HOSP^MR||Doe^Alex||" + birth + "|" + sex, "PV1|1|I|ICU^01^BED-21") + "\r";
    }

    // ---------------------------------------------------------------- IM-E11-BL-006
    @Test
    @Order(1)
    void hostDemographicsFromAdtPid7Pid8ChangeTheReferencePartition() throws Exception {
        ProbeIdentity.Creds gw = probes.demoGateway(T);
        TransportClient.Sent a01 = TransportClient.mllp("localhost", listeners.mllpPort(), gw.ssl(), adt("A01", "CTRL-0001", "20261106101500", "19760315", "F"));
        assertThat(a01.response()).contains("MSA|AA");
        ObjectNode s = store.require(T, "subject", SUBJECT);
        assertThat(s.get("birthDate").asString()).isEqualTo("1976-03-15");
        assertThat(s.get("sex").asString()).isEqualTo("female");
        assertThat(s.at("/demographicsProvenance/sex/source").asString()).isEqualTo("HL7_ADT");
        assertThat(s.at("/demographicsProvenance/sex/controlId").asString()).isEqualTo("CTRL-0001");
        Instant t1 = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        PipelineResult r1 = live(ablCfg, ABL, Map.of("hb", "12.5", "k", "4.0"), t1);
        assertThat(r1.status).as(r1.error).isEqualTo("FILED");
        JsonNode hb1 = fieldBySource(instanceOf(r1), "hb");
        assertThat(hb1.at("/interpretation/code").asString()).isEqualTo("N");
        assertThat(hb1.at("/referenceRange/partition").asString()).contains("female");
        // A08 (update patient information) with PID-8 = M → the same Hb is now low for the male partition
        TransportClient.Sent a08 = TransportClient.mllp("localhost", listeners.mllpPort(), gw.ssl(), adt("A08", "CTRL-0002", "20261106111500", "19760315", "M"));
        assertThat(a08.response()).contains("MSA|AA");
        assertThat(store.require(T, "subject", SUBJECT).get("sex").asString()).isEqualTo("male");
        PipelineResult r2 = live(ablCfg, ABL, Map.of("hb", "12.5", "k", "5.4"), t1.plusSeconds(5));
        JsonNode hb2 = fieldBySource(instanceOf(r2), "hb");
        assertThat(hb2.at("/interpretation/code").asString()).isEqualTo("L");
        assertThat(hb2.at("/referenceRange/partition").asString()).contains("male");
        // an older host message never overwrites newer host information
        TransportClient.mllp("localhost", listeners.mllpPort(), gw.ssl(), adt("A08", "CTRL-0000", "20261101080000", "19760315", "F"));
        assertThat(store.require(T, "subject", SUBJECT).get("sex").asString()).isEqualTo("male");
        // manual registry entries keep precedence only while newer than the host information
        ObjectNode m = store.require(T, "subject", SUBJECT);
        m.put("sex", "female");
        HostDemographics.markManual(m, "user:c21.nurse");
        store.put(T, "subject", SUBJECT, m, "ACTIVE", null);
        HostDemographics.Applied older = demographics.apply(T, SUBJECT, null, "M", "FHIR", Instant.now().minusSeconds(3600), "v1");
        assertThat(older.kept()).contains("sex");
        assertThat(store.require(T, "subject", SUBJECT).get("sex").asString()).isEqualTo("female");
        HostDemographics.Applied newer = demographics.apply(T, SUBJECT, null, "M", "FHIR", Instant.now().plusSeconds(60), "v2");
        assertThat(newer.updated()).contains("sex");
        assertThat(store.require(T, "subject", SUBJECT).get("sex").asString()).isEqualTo("male");
        // FHIR Patient create for machine clients with scope system/Patient.write; administrative sex U → no sex partition
        ObjectNode patient = Json.obj().put("resourceType", "Patient").put("birthDate", "2019-05-01").put("gender", "unknown");
        patient.putArray("identifier").addObject().put("value", "SUBJ-FHIR-7");
        ResponseEntity<Object> created = Current.as(client, () -> fhir.patientCreate(patient));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        ObjectNode fs = store.require(T, "subject", "SUBJ-FHIR-7");
        assertThat(fs.get("birthDate").asString()).isEqualTo("2019-05-01");
        assertThat(fs.has("sex")).isFalse();
        assertThat(fs.get("administrativeSex").asString()).isEqualTo("U");
        assertThat(fs.at("/demographicsProvenance/birthDate/source").asString()).isEqualTo("FHIR");
        IntiPrincipal noScope = new IntiPrincipal("x", "x", T, "x", EnumSet.noneOf(Role.class), Set.of("system/Observation.read"), true);
        assertThatThrownBy(() -> Current.as(noScope, () -> fhir.patientCreate(patient))).isInstanceOf(ApiException.class).hasMessageContaining("system/Patient.write");
    }

    // ---------------------------------------------------------------- IM-E14-BL-012 + IM-E14-BL-010
    @Test
    @Order(2)
    void deltaChecksEmitAnInformationalFlagEventAndDerivedValuesAreLabelled() {
        // the second ABL result of test 1 moved potassium 4.0 → 5.4 mmol/L within 48 h (threshold 1.0)
        ObjectNode inst = store.listByRef(T, "template-instance", "TPL-05").stream()
                .filter(i -> fieldBySource(i, "k") != null && fieldBySource(i, "k").has("delta")).findFirst().orElseThrow();
        JsonNode k = fieldBySource(inst, "k");
        assertThat(k.at("/delta/flagged").asBoolean()).isTrue();
        assertThat(k.at("/delta/threshold").asString()).contains("1");
        ObjectNode ev = events.recent(T, 50).stream().filter(e -> "observation.flagged".equals(e.get("type").asString())).findFirst().orElseThrow();
        assertThat(ev.at("/data/entry").asString()).isEqualTo("potassium");
        assertThat(ev.at("/data/flag").asString()).isEqualTo("delta");
        assertThat(ev.at("/data/codes/loinc").asString()).isEqualTo("6298-4");
        assertThat(Json.write(ev)).doesNotContain(SUBJECT).doesNotContain("\"value\"");
        // webhooks can subscribe to and deliver the event to the host (delivery attempted; host owns acknowledgement)
        Current.as(admin, () -> webhooks.create(T, "https://localhost:9/flags", List.of("observation.flagged"), "user:c21.admin"));
        live(ablCfg, ABL, Map.of("hb", "12.6", "k", "3.9"), Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(10));
        assertThat(webhooks.deliveries(T)).anySatisfy(d -> assertThat(d.get("type").asString()).isEqualTo("observation.flagged"));
        // derived values: eGFR from creatinine with the host demographics (male, ~50 y), labelled and versioned
        association.openWorklist(T, "C21-LAB", SUBJECT, "TPL-06", "ORD-C21-1", 60);
        PipelineResult r = live(cobasCfg, COBAS, Map.of("creatinine", "88"), Instant.now().truncatedTo(ChronoUnit.SECONDS));
        assertThat(r.status).as(() -> r.unmatchedId == null ? r.error : store.require(T, "unmatched", r.unmatchedId).toString()).isEqualTo("FILED");
        ObjectNode lab = instanceOf(r);
        JsonNode egfr = lab.at("/derived/egfr");
        assertThat(egfr.get("value").asDouble()).isBetween(88.0, 95.0);
        assertThat(egfr.get("formulaId").asString()).isEqualTo("CKD-EPI-2021-CR");
        assertThat(egfr.get("label").asString()).isEqualTo("derived – verify");
        assertThat(egfr.at("/inputs/sex").asString()).isEqualTo("male");
        assertThat(egfr.path("grade").asString()).startsWith("G1");
        // default OFF: the ABL configuration (no params.derivedValues) carries no derived block
        assertThat(inst.has("derived")).isFalse();
    }

    // ---------------------------------------------------------------- IM-E14-BL-011
    static final String CSV = "entry,ageFrom,ageTo,sex,pregnant,trimester,low,high,criticalLow,criticalHigh,unit,source\n"
            + "potassium,18,200,any,,,3.4,4.8,2.8,6.2,mmol/L,Local lab 2026\n"
            + "fev1-fvc,18,200,any,,,72,100,,,%,Local lab 2026\n";

    static List<BigDecimal> results(int outside, double inside, double out) {
        List<BigDecimal> l = new ArrayList<>();
        for (int i = 0; i < 20 - outside; i++) l.add(BigDecimal.valueOf(inside));
        for (int i = 0; i < outside; i++) l.add(BigDecimal.valueOf(out));
        return l;
    }

    @Test
    @Order(3)
    void intervalOverlayNeedsEp28AndFourEyesThenDrivesLiveAnnotation() {
        assertThatThrownBy(() -> Current.as(nurse, () -> intervals.importCsv(T, CSV, "user:c21.nurse"))).hasMessageContaining("Requires one of");
        assertThatThrownBy(() -> Current.as(reg1, () -> intervals.importCsv(T, CSV.replace("mmol/L", "mg/dL"), "user:c21.reg1"))).hasMessageContaining("unit");
        ObjectNode o = Current.as(reg1, () -> intervals.importCsv(T, CSV, "user:c21.reg1"));
        String id = o.get("id").asString();
        assertThat(o.get("status").asString()).isEqualTo("DRAFT");
        assertThat(o.get("partitions").size()).isEqualTo(2);
        assertThatThrownBy(() -> Current.as(reg2, () -> intervals.approve(T, id, "early", "user:c21.reg2"))).hasMessageContaining("EP28");
        // EP28: 3 of 20 outside → not verified; 2 of 20 → verified
        assertThat(Current.as(reg1, () -> intervals.verify(T, id, "potassium", 0, results(3, 4.1, 5.3), "user:c21.reg1")).get("pass").asBoolean()).isFalse();
        assertThat(intervals.get(T, id).get("status").asString()).isEqualTo("DRAFT");
        assertThat(Current.as(reg1, () -> intervals.verify(T, id, "potassium", 0, results(2, 4.1, 5.3), "user:c21.reg1")).get("pass").asBoolean()).isTrue();
        Current.as(reg1, () -> intervals.verify(T, id, "fev1-fvc", 0, results(1, 80, 65), "user:c21.reg1"));
        assertThat(intervals.get(T, id).get("status").asString()).isEqualTo("VERIFIED");
        // four-eyes: the importer cannot approve
        assertThatThrownBy(() -> Current.as(reg1, () -> intervals.approve(T, id, "self", "user:c21.reg1"))).hasMessageContaining("four-eyes");
        assertThat(Current.as(reg2, () -> intervals.approve(T, id, "verified locally", "user:c21.reg2")).get("status").asString()).isEqualTo("APPROVED");
        assertThat(Current.as(reg2, () -> intervals.activate(T, id, "user:c21.reg2")).get("status").asString()).isEqualTo("ACTIVE");
        // live annotation now uses the tenant overlay: 4.9 mmol/L is H (overlay 3.4–4.8) where the default (3.5–5.1) says N
        PipelineResult r = live(ablCfg, ABL, Map.of("hb", "13.5", "k", "4.9"), Instant.now().truncatedTo(ChronoUnit.SECONDS).plusSeconds(20));
        JsonNode k = fieldBySource(instanceOf(r), "k");
        assertThat(k.at("/interpretation/code").asString()).isEqualTo("H");
        assertThat(k.at("/referenceRange/source").asString()).startsWith("tenant overlay v1");
        assertThat(k.at("/referenceRange/overlayVersion").asInt()).isEqualTo(1);
        assertThat(audit.byTarget(T, IntervalGovernance.KIND + "/" + id)).extracting(x -> x.get("action")).contains("interval-overlay.import", "interval-overlay.verify",
                "interval-overlay.approve.refused", "interval-overlay.approve", "interval-overlay.activate");
        // FHIR ObservationDefinition import → a new versioned DRAFT
        ObjectNode od = (ObjectNode) Json.parse("{\"resourceType\":\"ObservationDefinition\",\"id\":\"na\",\"publisher\":\"Local lab\",\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"2951-2\"}]},"
                + "\"quantitativeDetails\":{\"unit\":{\"coding\":[{\"system\":\"http://unitsofmeasure.org\",\"code\":\"mmol/L\"}]}},\"qualifiedInterval\":["
                + "{\"category\":\"reference\",\"range\":{\"low\":{\"value\":136},\"high\":{\"value\":146}},\"age\":{\"low\":{\"value\":18,\"unit\":\"a\"}}},"
                + "{\"category\":\"critical\",\"range\":{\"high\":{\"value\":120}},\"age\":{\"low\":{\"value\":18,\"unit\":\"a\"}}}]}");
        ObjectNode v2 = Current.as(reg1, () -> intervals.importObservationDefinitions(T, od, "user:c21.reg1"));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.at("/entries/sodium/0/low").asDouble()).isEqualTo(136.0);
        assertThat(v2.at("/entries/sodium/0/criticalLow").asDouble()).isEqualTo(120.0);
        assertThat(v2.get("status").asString()).isEqualTo("DRAFT");
    }

    // ---------------------------------------------------------------- IM-E12-BL-009 RR-17 + XT-17 against the overlay
    String provision(String driver, String modelId, ObjectNode site) {
        return Current.as(admin, () -> {
            ObjectNode c = configs.create(T, "C21 " + driver, modelId, site, "user:c21.admin");
            String id = c.get("id").asString();
            configs.saveStep(T, id, 1, Json.obj().put("driver", driver), "user:c21.admin");
            for (int s = 2; s <= 6; s++) {
                configs.recommendations(T, id, s);
                configs.acceptAll(T, id, s, "user:c21.admin");
                ObjectNode body = Json.obj();
                if (s == 3) body.put("endpointId", probes.demoGateway(T).endpointId());
                configs.saveStep(T, id, s, body, "user:c21.admin");
            }
            return id;
        });
    }

    @Test
    @Order(4)
    void rr17RecordsThePlatformCategoryAndXt17PassesAgainstTheTenantOverlay() {
        spiroCfg = provision(SPIRO, "IMD-001", Json.obj().put("site", "OH clinic").put("station", "C21-SPIRO"));
        ObjectNode cfg = configs.get(T, spiroCfg);
        assertThat(cfg.get("platformCategory").asString()).isEqualTo("OCC_HEALTH");
        assertThat(cfg.path("decisions").toString()).contains("RR-17");
        ObjectNode run = Current.as(admin, () -> harness.run(T, spiroCfg, "user:c21.admin"));
        assertThat(run.at("/results/XT-17/status").asString()).as(Json.pretty(run.at("/results/XT-17"))).isEqualTo("PASS");
        assertThat(run.get("status").asString()).as(Json.pretty(run.get("results"))).isEqualTo("PASSED");
        assertThat(run.at("/results/XT-17").toString()).contains("configuration (RR-17)");
        ObjectNode sandbox = store.listByRef(T, "sandbox-instance", run.get("id").asString()).stream().filter(i -> i.at("/fields/ratio").isObject()).findFirst().orElse(null);
        if (sandbox != null) assertThat(sandbox.at("/fields/ratio/referenceRange/source").asString()).startsWith("tenant overlay v1");
    }

    // ---------------------------------------------------------------- IM-E12-BL-009 RR-18 + IM-E12-BL-010 terminology
    @Test
    @Order(5)
    void rr18AutoMapSuggestionsForHostTemplatesUseTheTerminologyServerAndNeverBulkAcceptBelow08() {
        ObjectNode q = (ObjectNode) Json.parse("{\"resourceType\":\"Questionnaire\",\"id\":\"spiro-host\",\"title\":\"Host spirometry form\",\"item\":["
                + "{\"linkId\":\"fev1\",\"text\":\"FEV1\",\"type\":\"quantity\",\"code\":[{\"system\":\"http://loinc.org\",\"code\":\"20150-9\"}],\"extension\":[{\"url\":\"http://hl7.org/fhir/StructureDefinition/questionnaire-unit\",\"valueCoding\":{\"code\":\"L\"}}]},"
                + "{\"linkId\":\"fvc\",\"text\":\"FVC\",\"type\":\"quantity\",\"extension\":[{\"url\":\"http://hl7.org/fhir/StructureDefinition/questionnaire-unit\",\"valueCoding\":{\"code\":\"L\"}}]},"
                + "{\"linkId\":\"tiff\",\"text\":\"Tiffeneau index\",\"type\":\"decimal\",\"code\":[{\"system\":\"http://hospital.example/codes\",\"code\":\"TIFF\"}],\"extension\":[{\"url\":\"http://hl7.org/fhir/StructureDefinition/questionnaire-unit\",\"valueCoding\":{\"code\":\"%\"}}]}]}");
        ObjectNode tpl = Current.as(owner, () -> templates.importQuestionnaire(T, q, "user:c21.owner"));
        String tid = tpl.get("id").asString();
        assertThat(tpl.at("/sections/0/fields/2/code/system").asString()).isEqualTo("http://hospital.example/codes");
        ArrayNode recs = Current.as(admin, () -> configs.recommendations(T, spiroCfg, 5));
        List<JsonNode> rr18 = new ArrayList<>();
        recs.forEach(r -> { if ("RR-18".equals(r.path("rule").asString()) && tid.equals(r.path("templateId").asString())) rr18.add(r); });
        assertThat(recs.toString()).contains("RR-17");
        JsonNode fev1 = rr18.stream().filter(r -> "fev1".equals(r.get("field").asString())).findFirst().orElseThrow();
        JsonNode fvc = rr18.stream().filter(r -> "fvc".equals(r.get("field").asString())).findFirst().orElseThrow();
        JsonNode tiff = rr18.stream().filter(r -> "tiff".equals(r.get("field").asString())).findFirst().orElseThrow();
        assertThat(fev1.get("value").asString()).isEqualTo("fev1");
        assertThat(fev1.get("confidenceScore").asDouble()).isGreaterThanOrEqualTo(0.8);
        assertThat(fvc.get("value").asString()).isEqualTo("fvc");
        assertThat(fvc.get("confidenceScore").asDouble()).isLessThan(0.8);
        assertThat(fvc.get("level").asString()).isEqualTo("REVIEW");
        assertThat(fvc.get("policy").asString()).isEqualTo("WARN");
        // the unknown hospital code is translated to LOINC 19926-5 by the terminology server → FEV1/FVC ratio
        assertThat(tiff.get("value").asString()).isEqualTo("ratio");
        assertThat(tiff.get("why").asString()).contains("terminology $translate");
        assertThat(stubCalls.get()).isPositive();
        // 'accept all' (DEV) never accepts RR-18 suggestions below 0.8 – they need an individual human decision
        ArrayNode done = Current.as(admin, () -> configs.acceptAll(T, spiroCfg, 5, "user:c21.admin"));
        List<String> accepted = new ArrayList<>();
        done.forEach(d -> accepted.add(d.get("id").asString()));
        assertThat(accepted).contains(fev1.get("id").asString(), tiff.get("id").asString()).doesNotContain(fvc.get("id").asString());
        assertThat(store.require(T, "recommendation", fvc.get("id").asString()).get("status").asString()).isEqualTo("PROPOSED");
        ObjectNode decided = Current.as(admin, () -> configs.decide(T, fvc.get("id").asString(), "ACCEPT", null, null, "user:c21.admin"));
        assertThat(decided.get("status").asString()).isEqualTo("ACCEPTED");
        JsonNode hostBinding = null;
        for (JsonNode b : configs.get(T, spiroCfg).path("bindings")) if (tid.equals(b.path("templateId").asString())) hostBinding = b;
        assertThat(hostBinding).isNotNull();
        assertThat(hostBinding.at("/fields/tiff").asString()).isEqualTo("ratio");
        assertThat(hostBinding.at("/fields/fvc").asString()).isEqualTo("fvc");
        assertThat(audit.byTarget(T, "config/" + spiroCfg).toString()).contains("RR-18");
    }

    @Test
    @Order(6)
    void terminologyClientLooksUpValidatesTranslatesCachesAndRefusesUnsafeTargets() {
        assertThat(terminology.enabled()).isTrue();
        int before = stubCalls.get();
        assertThat(terminology.lookup("http://hospital.example/codes", "TIFF").orElseThrow().display()).contains("Tiffeneau");
        assertThat(terminology.validateCode("http://hospital.example/codes", "TIFF")).contains(true);
        assertThat(terminology.validateCode("http://hospital.example/codes", "NOPE")).contains(false);
        List<TerminologyService.Mapping> m = terminology.translate("http://hospital.example/codes", "TIFF", "http://loinc.org");
        assertThat(m).extracting(TerminologyService.Mapping::code).containsExactly("19926-5");
        int after = stubCalls.get();
        terminology.translate("http://hospital.example/codes", "TIFF", "http://loinc.org");
        assertThat(stubCalls.get()).as("cached").isEqualTo(after);
        assertThat(after).isGreaterThanOrEqualTo(before);
        assertThat(terminology.status().get("cacheHits").asLong()).isPositive();
        // SSRF guard: plain HTTP and hosts outside the allow-list are refused (adapter disabled with a reason)
        FhirTerminologyClient http = new FhirTerminologyClient(true, "http://localhost:" + stub.getAddress().getPort() + "/fhir", "localhost", 1000, 1, true);
        assertThat(http.enabled()).isFalse();
        assertThat(http.status().get("reason").asString()).contains("HTTPS");
        FhirTerminologyClient other = new FhirTerminologyClient(true, "https://tx.example.org/fhir", "localhost", 1000, 1, false);
        assertThat(other.enabled()).isFalse();
        assertThat(other.lookup("http://hospital.example/codes", "TIFF")).isEmpty();
        assertThat(new FhirTerminologyClient(false, "", "", 1000, 1, false).status().get("reason").asString()).contains("disabled");
    }

    // ---------------------------------------------------------------- IM-E15-BL-003
    void obs(String cfg, String key, double... values) {
        for (double v : values) {
            String id = "obs-" + cfg + "-" + io.intimed.common.Hashing.randomToken(6).replaceAll("[^A-Za-z0-9]", "x");
            ObjectNode o = Json.obj().put("id", id).put("status", "FILED").put("mode", "LIVE").put("configId", cfg);
            o.putObject("driver").put("ref", "oximeter.nonin.3230-ble@1.0.0");
            o.putArray("measurements").addObject().put("key", key).put("value", BigDecimal.valueOf(v)).put("unit", "%");
            store.put(D, "observation", id, o, "FILED", cfg);
        }
    }

    @Test
    @Order(7)
    void driftAlertsAreAcknowledgedLinkedToCalibrationAutoResolvedAndForwardedToSiem() throws Exception {
        IntiPrincipal biomed = users.create(D, "d21.biomed", PW, "Biomed", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        ObjectNode c4 = Json.obj().put("id", "cfg-d4").put("name", "ward 4 oximeter").put("status", "PUBLISHED");
        c4.putObject("calibration").putArray("history").addObject().put("at", "2026-11-01T10:00:00Z").put("by", "user:d21.biomed").put("passed", true).put("note", "probe replaced, recalibrated");
        store.put(D, "config", "cfg-d4", c4, "PUBLISHED", "IMD-x");
        obs("cfg-d1", "spo2", 97, 98, 97);
        obs("cfg-d2", "spo2", 96, 97, 98);
        obs("cfg-d3", "spo2", 98, 97, 96);
        obs("cfg-d4", "spo2", 88, 87, 88);
        ObjectNode a1 = drift.analyse(D, "user:d21.biomed");
        assertThat(a1.get("raised").asInt()).isEqualTo(1);
        ObjectNode alert = drift.alerts(D).stream().filter(a -> "cfg-d4".equals(a.get("configId").asString())).findFirst().orElseThrow();
        String id = alert.get("id").asString();
        assertThat(alert.get("status").asString()).isEqualTo("OPEN");
        assertThat(alert.get("siemForwarded").asBoolean()).isTrue();
        // re-analysis updates the open alert instead of duplicating it
        drift.analyse(D, "user:d21.biomed");
        assertThat(drift.alerts(D).stream().filter(a -> "cfg-d4".equals(a.get("configId").asString())).count()).isEqualTo(1);
        assertThatThrownBy(() -> drift.acknowledge(D, id, " ", null, "user:d21.biomed")).hasMessageContaining("note");
        assertThatThrownBy(() -> drift.acknowledge(D, id, "checking", "2020-01-01T00:00:00Z", "user:d21.biomed")).hasMessageContaining("calibration");
        ObjectNode acked = Current.as(biomed, () -> drift.acknowledge(D, id, "probe placement checked; recalibrated", "2026-11-01T10:00:00Z", "user:d21.biomed"));
        assertThat(acked.get("status").asString()).isEqualTo("ACKNOWLEDGED");
        assertThat(acked.at("/calibration/note").asString()).contains("recalibrated");
        // the next analysis no longer alerts → auto-resolved
        double[] normal = new double[30];
        java.util.Arrays.fill(normal, 97);
        obs("cfg-d4", "spo2", normal);
        ObjectNode a2 = drift.analyse(D, "user:d21.biomed");
        assertThat(a2.get("autoResolved").asInt()).isEqualTo(1);
        ObjectNode resolved = store.require(D, DriftMonitor.KIND, id);
        assertThat(resolved.get("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolution").asString()).isEqualTo("AUTO");
        // drifting again → a new alert, resolved manually with a note
        double[] low = new double[100];
        java.util.Arrays.fill(low, 80);
        obs("cfg-d4", "spo2", low);
        drift.analyse(D, "user:d21.biomed");
        ObjectNode again = drift.alerts(D).stream().filter(a -> "cfg-d4".equals(a.get("configId").asString()) && "OPEN".equals(a.get("status").asString())).findFirst().orElseThrow();
        assertThat(again.get("id").asString()).isNotEqualTo(id);
        ObjectNode manual = drift.resolve(D, again.get("id").asString(), "device withdrawn for service", null, "user:d21.biomed");
        assertThat(manual.get("resolution").asString()).isEqualTo("MANUAL");
        assertThatThrownBy(() -> drift.resolve(D, again.get("id").asString(), "again", null, "user:d21.biomed")).hasMessageContaining("already resolved");
        assertThat(audit.byTarget(D, DriftMonitor.KIND + "/" + id)).extracting(x -> x.get("action")).contains("drift.alert.raised", "drift.alert.ack", "drift.alert.auto-resolved");
        String siem = Files.readString(Path.of(System.getProperty("intimed.home")).resolve("siem").resolve("audit.jsonl"));
        if (!siem.contains("drift.alert.ack")) {
            // the shared Spring context may have been created by another test class with its own intimed.home
            try (var files = Files.walk(Path.of(System.getProperty("java.io.tmpdir")), 3)) {
                siem = files.filter(p -> p.endsWith(Path.of("siem", "audit.jsonl"))).map(p -> { try { return Files.readString(p); } catch (Exception e) { return ""; } })
                        .filter(x -> x.contains("drift.alert.ack")).findFirst().orElse("");
            }
        }
        assertThat(siem).contains("drift.alert.ack").contains("drift.alert.auto-resolved").contains("drift.alert.resolve");
    }

    // ---------------------------------------------------------------- IM-E13-BL-005
    @Test
    @Order(8)
    void sepsisTrajectoryRendersThroughAContinuousDriverWithFlagsAndNews2() {
        ObjectNode r = cohorts.trajectory(T, null, "traj-sepsis-6h", 0, 60, 7, Instant.parse("2026-03-02T06:00:00Z"), null);
        assertThat(r.get("captureMode").asString()).isEqualTo("CONTINUOUS");
        assertThat(r.get("steps").asInt()).isEqualTo(7);
        JsonNode first = r.get("series").get(0), last = r.get("series").get(6);
        assertThat(last.get("minute").asInt()).isEqualTo(360);
        assertThat(last.at("/flags/heart-rate").asString()).isIn("H", "HH");
        assertThat(last.at("/flags/spo2").asString()).isIn("L", "LL");
        assertThat(last.at("/news2/score").asInt()).isGreaterThan(first.at("/news2/score").asInt());
        assertThat(r.at("/summary/maxNews2").asInt()).isGreaterThanOrEqualTo(7);
        assertThat(r.get("disclaimer").asString()).contains("no alarms");
        // deterministic by seed
        ObjectNode again = cohorts.trajectory(T, r.get("driver").asString(), "traj-sepsis-6h", 0, 60, 7, Instant.parse("2026-03-02T06:00:00Z"), null);
        assertThat(again.get("series").toString()).isEqualTo(r.get("series").toString());
        assertThatThrownBy(() -> cohorts.trajectory(T, null, "adult-normal", 3, 30, 1, Instant.now(), null)).hasMessageContaining("no trajectory");
    }
}
