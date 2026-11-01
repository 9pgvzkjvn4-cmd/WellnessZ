package io.intimed;

import io.intimed.capture.CaptureService;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.console.intelligence.KnowledgeBase;
import io.intimed.engine.association.AssociationEngine;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.sim.Simulator;
import io.intimed.engine.transport.DeviceListeners;
import io.intimed.engine.transport.TransportClient;
import io.intimed.harness.EvidenceSealer;
import io.intimed.harness.HarnessService;
import io.intimed.harness.ProbeIdentity;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.UserService;
import io.intimed.security.endpoint.AnomalyService;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.security.pki.LocalPki;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration tests on a real running platform (HTTPS console, MLLP-TLS and mTLS ingress on random ports,
 * encrypted H2 store): provisioning with intelligence → exchange test → four-eyes → publish gate → live capture;
 * security behaviour (mTLS refusals, isolation, audit tamper detection, tenant isolation, guardrails, HTTP controls).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "intimed.device.mllp-port=0", "intimed.device.ingress-port=0", "management.server.port=0", "intimed.demo.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformIntegrationTest {
    static final String A = "t-alpha", B = "t-beta", PW = "Integration-Test-2026";

    static {
        try {
            Path home = Files.createTempDirectory("intimed-it");
            System.setProperty("intimed.home", home.toString());
            System.setProperty("spring.datasource.url", "jdbc:h2:file:" + home + "/db/intimed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CIPHER=AES");
            IntiMedApplication.prepareTls();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Autowired TenantService tenants;
    @Autowired UserService users;
    @Autowired ConfigService configs;
    @Autowired HarnessService harness;
    @Autowired DriverRegistry drivers;
    @Autowired DocumentStore store;
    @Autowired AuditService audit;
    @Autowired JdbcClient jdbc;
    @Autowired ProbeIdentity probes;
    @Autowired CaptureService capture;
    @Autowired AssociationEngine association;
    @Autowired EndpointService endpoints;
    @Autowired AnomalyService anomalies;
    @Autowired DeviceListeners listeners;
    @Autowired EvidenceSealer sealer;
    @Autowired KnowledgeBase kb;
    @Autowired Environment env;

    IntiPrincipal admin, approver, nurse, soc;
    String cfgId;

    @BeforeAll
    void setup() {
        tenants.create(A, "Alpha", "eu-west", "ENTERPRISE", null, "test");
        tenants.create(B, "Beta", "us-east", "TRIAL", null, "test");
        admin = users.create(A, "it.admin", PW, "Admin", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        approver = users.create(A, "it.approver", PW, "Approver", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        nurse = users.create(A, "it.nurse", PW, "Nurse", EnumSet.of(Role.CLINICIAN), "test").principal();
        soc = users.create(A, "it.soc", PW, "SOC", EnumSet.of(Role.SECURITY_ANALYST), "test").principal();
        Current.as(soc, () -> {
            new io.intimed.catalogue.CatalogueService(drivers, store, audit).recordPosture(A, "IMD-001",
                    Json.obj().put("mds2", "RECEIVED").put("sbom", "RECEIVED").put("knownVulnerabilities", 0), "user:it.soc");
            return null;
        });
    }

    String provision(String driver, String modelId, ObjectNode site) {
        return Current.as(admin, () -> {
            ObjectNode c = configs.create(A, "IT " + driver, modelId, site, "user:it.admin");
            String id = c.get("id").asString();
            configs.saveStep(A, id, 1, Json.obj().put("driver", driver), "user:it.admin");
            for (int s = 2; s <= 6; s++) {
                configs.recommendations(A, id, s);
                configs.acceptAll(A, id, s, "user:it.admin");
                ObjectNode body = Json.obj();
                if (s == 3) body.put("endpointId", probes.demoGateway(A).endpointId());
                configs.saveStep(A, id, s, body, "user:it.admin");
            }
            return id;
        });
    }

    @Test
    @Order(1)
    void provisioningWithIntelligenceExchangeTestFourEyesAndPublishGate() {
        cfgId = provision("spiro.ndd.easyone-gdt@1.0.0", "IMD-001", Json.obj().put("site", "OH clinic").put("station", "OH-SPIRO-1"));
        ObjectNode cfg = configs.get(A, cfgId);
        assertThat(cfg.get("option").asString()).isEqualTo("P2");
        assertThat(cfg.at("/association/method").asString()).isEqualTo("WORKLIST");
        assertThat(cfg.at("/qualityGates/grading").asString()).isEqualTo("ATS_ERS_2019");
        assertThat(cfg.path("decisions").size()).isGreaterThan(5);
        // publish gate before any test (SC-20)
        assertThatThrownBy(() -> Current.as(admin, () -> configs.publish(A, cfgId, "DEV", "user:it.admin"))).isInstanceOf(ApiException.class).hasMessageContaining("publish gate");
        ObjectNode run = Current.as(admin, () -> harness.run(A, cfgId, "user:it.admin"));
        assertThat(run.get("status").asString()).as(Json.pretty(run.get("results"))).isEqualTo("PASSED");
        assertThat(run.get("passed").asInt()).isEqualTo(run.get("total").asInt()).isGreaterThanOrEqualTo(15);
        assertThat(sealer.verify(sealer.read(run.get("id").asString())).get("valid")).isEqualTo(true);
        // four-eyes
        Current.as(admin, () -> configs.submitForCertification(A, cfgId, "user:it.admin"));
        assertThatThrownBy(() -> Current.as(admin, () -> configs.approve(A, cfgId, "self", "user:it.admin"))).hasMessageContaining("four-eyes");
        ObjectNode certified = Current.as(approver, () -> configs.approve(A, cfgId, "ok", "user:it.approver"));
        assertThat(certified.get("status").asString()).isEqualTo("CERTIFIED");
        assertThat(store.get(A, "certification", certified.get("certificationId").asString())).isPresent();
        ObjectNode pub = Current.as(admin, () -> configs.publish(A, cfgId, "DEV", "user:it.admin"));
        assertThat(pub.get("status").asString()).isEqualTo("PUBLISHED");
        // PROD needs SRE four-eyes; UAT needs the gate (already passed)
        Current.as(admin, () -> configs.publish(A, cfgId, "UAT", "user:it.admin"));
        assertThatThrownBy(() -> Current.as(admin, () -> configs.publish(A, cfgId, "PROD", "user:it.admin"))).hasMessageContaining("Platform SRE");
        // knowledge base contribution is de-identified
        String kbDump = Json.write(kb.export());
        assertThat(kbDump).contains("spiro.ndd.easyone-gdt").doesNotContain(A).doesNotContain("OH-SPIRO-1").doesNotContain("ProgramData");
    }

    @Test
    @Order(2)
    void liveCaptureOverMtlsFilesIntoTheTemplateAndPhiIsEncryptedAtRest() {
        DriverPackage d = drivers.require(A, "spiro.ndd.easyone-gdt@1.0.0");
        Current.as(nurse, () -> association.openWorklist(A, "OH-SPIRO-1", "EMP-1", "TPL-18", "ORD-1", 60));
        ObjectNode cfg = configs.get(A, cfgId);
        ObjectNode r = Current.as(nurse, () -> capture.sendLive(A, cfg, Simulator.render(d, Simulator.values(d), "EMP-1", Instant.now().truncatedTo(ChronoUnit.SECONDS), "EASYONE-AIR-200381")));
        assertThat(r.path("status").asString()).as(Json.pretty(r)).isEqualTo("FILED");
        assertThat(r.get("transport").asString()).contains("TLSv1.");
        List<ObjectNode> inst = store.listByRef(A, "template-instance", "TPL-18");
        assertThat(inst).anySatisfy(i -> { assertThat(i.get("subject").asString()).isEqualTo("EMP-1"); assertThat(i.at("/fields/fev1/value").decimalValue()).isPositive(); });
        String obsId = r.get("observationIds").get(0).asString();
        assertThat(store.rawStored(A, "observation", obsId).orElseThrow()).startsWith("enc:v1:").doesNotContain("EMP-1");
        // re-send the same message → idempotent
        ObjectNode again = Current.as(nurse, () -> capture.sendLive(A, cfg, store.listByRef(A, "raw", cfgId).stream().filter(x -> "LIVE".equals(x.path("mode").asString())).findFirst().orElseThrow().get("payload").asString()));
        assertThat(again.get("status").asString()).isEqualTo("DUPLICATE");
    }

    @Test
    @Order(3)
    void technicalChangeAfterTestRequiresRetest() {
        String id = provision("bp.and.ua651-ble@1.0.0", "IMD-037", Json.obj().put("site", "Clinic").put("station", "ST-BP"));
        // template owner decision: this cuff feeds the RPM dashboard only (TPL-02 also needs SpO2 and temperature)
        ObjectNode bp = configs.get(A, id);
        Current.as(admin, () -> configs.saveStep(A, id, 5, Json.obj().set("bindings", Json.arr().add(bp.get("bindings").get(0))), "user:it.admin"));
        ObjectNode run = Current.as(admin, () -> harness.run(A, id, "user:it.admin"));
        assertThat(run.get("status").asString()).as(Json.pretty(run.get("results"))).isEqualTo("PASSED");
        ObjectNode changed = Current.as(admin, () -> configs.saveStep(A, id, 4, Json.obj().set("params", Json.obj().put("reconnect", "ON_DEMAND")), "user:it.admin"));
        assertThat(changed.get("status").asString()).isEqualTo("DRAFT");
        assertThat(changed.path("hash").asString()).isNotEqualTo(changed.path("lastPassingRunHash").asString());
        assertThatThrownBy(() -> Current.as(admin, () -> configs.submitForCertification(A, id, "user:it.admin"))).isInstanceOf(ApiException.class);
        // insecure value is blocked by tenant policy regardless of recommendations (RR-03)
        ObjectNode blocked = Current.as(admin, () -> configs.saveStep(A, id, 4, Json.obj().set("params", Json.obj().put("pairing", "JUST_WORKS")), "user:it.admin"));
        assertThat(blocked.get("blocked").asBoolean()).isTrue();
        assertThat(blocked.get("violations").toString()).contains("RR-03");
    }

    @Test
    @Order(4)
    void acceptAllIsRefusedOutsideDevelopmentStages() {
        String id = Current.as(admin, () -> configs.create(A, "guardrail", "IMD-037", null, "user:it.admin").get("id").asString());
        ObjectNode c = configs.get(A, id);
        c.put("stage", "UAT");
        store.put(A, "config", id, c, "DRAFT", "IMD-037");
        assertThatThrownBy(() -> Current.as(admin, () -> configs.acceptAll(A, id, 2, "user:it.admin"))).hasMessageContaining("SC-19");
    }

    @Test
    @Order(5)
    void deviceListenersRefusePlaintextMissingCertificatesAndRevokedEndpoints() throws Exception {
        assertThat(TransportClient.plaintextProbe("localhost", listeners.mllpPort()).get("refused")).isEqualTo(true);
        var noCert = LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null);
        assertThat(TransportClient.handshakeProbe("localhost", listeners.mllpPort(), noCert, null).get("refused")).isEqualTo(true);
        ProbeIdentity.Creds gw = probes.demoGateway(A);
        Current.as(soc, () -> endpoints.setStatus(A, gw.endpointId(), "REVOKED", "test", "user:it.soc"));
        ObjectNode cfg = configs.get(A, cfgId);
        String refused;
        try { refused = TransportClient.ingress("localhost", listeners.ingressPort(), gw.ssl(), Json.obj().put("configId", cfgId).put("option", "P2").put("payload", "x")).response(); }
        catch (Exception e) { refused = "handshake refused: " + e.getClass().getSimpleName(); }
        assertThat(refused).containsAnyOf("endpoint not active", "handshake refused");
        assertThat(anomalies.alerts(A)).anySatisfy(a -> assertThat(a.get("signal").asString()).isEqualTo("UNKNOWN_IDENTITY"));
        probes.invalidate();
        assertThat(cfg).isNotNull();
    }

    @Test
    @Order(6)
    void anomalyIsolationAndRecertificationGate() {
        probes.demoGateway(A); // re-enrolled after the revocation test
        String ep = Current.as(admin, () -> endpoints.register(A, "IT gateway", "GATEWAY", "ICU", "MDZ-1", "user:it.admin").endpoint().get("id").asString());
        endpoints.bindConfig(A, ep, cfgId);
        ObjectNode alert = null;
        for (int i = 0; i < 6 && alert == null; i++) alert = anomalies.signal(A, ep, "PARSE_ERROR_BURST", "fuzz");
        assertThat(alert).isNotNull();
        String alertId = alert.get("id").asString();
        ObjectNode iso = Current.as(soc, () -> anomalies.isolate(A, alertId, "user:it.soc"));
        assertThat(iso.get("withinTarget").asBoolean()).isTrue();
        assertThat(configs.get(A, cfgId).get("recertificationRequired").asBoolean()).isTrue();
        assertThatThrownBy(() -> Current.as(soc, () -> anomalies.release(A, ep, "user:it.soc"))).hasMessageContaining("passing exchange test");
        ObjectNode rerun = Current.as(admin, () -> harness.run(A, cfgId, "user:it.admin"));
        assertThat(rerun.get("status").asString()).as(Json.pretty(rerun.get("results"))).isEqualTo("PASSED");
        assertThat(Current.as(soc, () -> anomalies.release(A, ep, "user:it.soc")).get("status").asString()).isEqualTo("ACTIVE");
    }

    @Test
    @Order(7)
    void tenantIsolationAndPerTenantKeys() {
        assertThat(store.get(B, "config", cfgId)).isEmpty();
        assertThat(store.list(B, "observation")).isEmpty();
        String obsId = store.list(A, "observation").get(0).get("id").asString();
        String cipher = store.rawStored(A, "observation", obsId).orElseThrow();
        jdbc.sql("insert into documents(tenant_id, kind, id, data, status, ref, created_at, updated_at, version) values (:t,'observation',:i,:d,'FILED',null,current_timestamp,current_timestamp,1)")
                .param("t", B).param("i", obsId).param("d", cipher).update();
        assertThatThrownBy(() -> store.get(B, "observation", obsId)).isInstanceOf(SecurityException.class);
    }

    @Test
    @Order(8)
    void auditChainDetectsTampering() {
        audit.record(A, "user:x", "test.event", "t/1", Map.of("k", "v"));
        assertThat(audit.verify().get("valid")).isEqualTo(true);
        long seq = jdbc.sql("select max(seq) from audit_log").query(Long.class).single();
        jdbc.sql("update audit_log set actor = 'user:mallory' where seq = :s").param("s", seq - 1).update();
        Map<String, Object> v = audit.verify();
        assertThat(v.get("valid")).isEqualTo(false);
        assertThat(((Number) v.get("brokenAt")).longValue()).isEqualTo(seq - 1);
    }

    @Test
    @Order(9)
    void httpSecurityControls() throws Exception {
        String base = "https://localhost:" + env.getProperty("local.server.port");
        HttpClient c = HttpClient.newBuilder().sslContext(LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(base + "/api/overview")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(401);
        assertThat(r.headers().firstValue("Content-Security-Policy").orElse("")).contains("script-src 'self'").doesNotContain("unsafe-inline");
        assertThat(r.headers().firstValue("Strict-Transport-Security")).isPresent();
        HttpResponse<String> login = c.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"it.admin\",\"password\":\"" + PW + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(login.statusCode()).as("login without CSRF token").isEqualTo(403);
        assertThat(c.send(HttpRequest.newBuilder(URI.create(base + "/fhir/r4/Observation")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(c.send(HttpRequest.newBuilder(URI.create(base + "/fhir/r4/metadata")).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        HttpResponse<String> wh = c.send(HttpRequest.newBuilder(URI.create(base + "/api/webhooks/" + A + "/" + cfgId)).header("X-IntiMed-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                .header("X-IntiMed-Signature", "v1=deadbeef").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wh.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(10)
    void passwordPolicyAndLockout() {
        assertThatThrownBy(() -> users.create(A, "weak.user", "short", "Weak", Set.of(Role.CLINICIAN), "test")).hasMessageContaining("password");
        for (int i = 0; i < UserService.MAX_FAILURES; i++) assertThatThrownBy(() -> users.authenticate("it.nurse", "wrong-password-1A")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> users.authenticate("it.nurse", PW)).hasMessageContaining("locked");
    }

    @Test
    @Order(11)
    void pipelineThroughputForTheCapacityModel() throws Exception {
        tenants.updateSettings(A, (ObjectNode) Json.parse("{\"quotas\":{\"messagesPerSecond\":100000,\"burst\":100000}}"), "test");
        String id = provision("vitals.welchallyn.csm-pcd01@1.0.0", "IMD-027", Json.obj().put("site", "ED").put("station", "ED-PERF"));
        DriverPackage d = drivers.require(A, "vitals.welchallyn.csm-pcd01@1.0.0");
        var pipeline = org.springframework.test.util.ReflectionTestUtils.getField(capture, "pipeline");
        int n = 2000;
        Instant t0 = Instant.parse("2026-11-01T00:00:00Z");
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            String msg = Simulator.render(d, Simulator.vary(Simulator.values(d), i), null, t0.plusSeconds(i), "CSM-100377");
            ((io.intimed.engine.pipeline.Pipeline) pipeline).process(new io.intimed.engine.pipeline.Envelope(A, null, id, "P4", msg, "TEST", "PERF-SUBJECT", "perf", "local", Instant.now()));
        }
        double secs = (System.nanoTime() - start) / 1e9;
        double rate = n / secs;
        Files.writeString(Path.of(System.getProperty("intimed.home")).resolve("perf.txt"), String.format("%d messages in %.2f s = %.0f msg/s (single node, in-process, encrypted H2)%n", n, secs, rate));
        System.out.printf("CAPACITY: %d HL7 PCD-01 messages in %.2f s = %.0f msg/s%n", n, secs, rate);
        assertThat(rate).isGreaterThan(50);
    }
}
