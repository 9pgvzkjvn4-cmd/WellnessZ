package io.intimed;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.intimed.catalogue.CatalogueService;
import io.intimed.catalogue.RegisterFeedService;
import io.intimed.catalogue.UdiFeedService;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.console.intelligence.DocAssistant;
import io.intimed.console.intelligence.ExplanationService;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.driver.Signatures;
import io.intimed.events.EventBus;
import io.intimed.harness.ProbeIdentity;
import io.intimed.partner.BadgeService;
import io.intimed.partner.PartnerSubmissionService;
import io.intimed.security.RateLimiter;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.UserService;
import io.intimed.security.pki.LocalPki;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
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
import org.springframework.core.env.Environment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Partner programme and catalogue feeds on the running platform (shares the application context of
 * {@link PlatformIntegrationTest}): vendor submission lifecycle with four-eyes certification (IM-E02-BL-006), badges
 * with automatic revocation (IM-E07-BL-005), AccessGUDID / EUDAMED change detection against a local stub
 * (IM-E02-BL-003), LLM policy gateway (IM-E03-BL-006) and the runtime OpenAPI document (IM-E07-BL-004).
 * No test touches the internet: every outbound call goes to a JDK HttpServer on 127.0.0.1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "intimed.device.mllp-port=0", "intimed.device.ingress-port=0", "management.server.port=0", "intimed.demo.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PartnerCatalogueTest {
    static final String P = "t-partner", PW = "Integration-Test-2026";
    static final String DRIVER_ID = "partner.acme.spiro-gdt";

    static { TestHome.init(); }

    @Autowired TenantService tenants;
    @Autowired UserService users;
    @Autowired ConfigService configs;
    @Autowired DriverRegistry drivers;
    @Autowired DocumentStore store;
    @Autowired AuditService audit;
    @Autowired ProbeIdentity probes;
    @Autowired CatalogueService catalogue;
    @Autowired RegisterFeedService feeds;
    @Autowired UdiFeedService udiDefault;
    @Autowired PartnerSubmissionService submissions;
    @Autowired BadgeService badges;
    @Autowired EventBus events;
    @Autowired DocAssistant docs;
    @Autowired ExplanationService explanationDefault;
    @Autowired SecretStore secrets;
    @Autowired RateLimiter limiter;
    @Autowired Environment env;

    IntiPrincipal vendor, vendor2, rev1, rev2, soc, tadmin;
    KeyPair vendorKey;
    HttpServer stub;
    final Map<String, String> stubBodies = new ConcurrentHashMap<>();
    final Map<String, String> stubHeaders = new ConcurrentHashMap<>();
    final AtomicInteger llmCalls = new AtomicInteger();
    volatile String gudidBrand = "EasyOne Air";
    String submissionId, driverRef, configId, runId;

    @BeforeAll
    void setup() throws IOException {
        tenants.create(P, "Partner hospital", "eu-west", "ENTERPRISE", null, "test");
        vendor = users.create(P, "acme.dev", PW, "ACME developer", EnumSet.of(Role.PARTNER_DEV), "test").principal();
        vendor2 = users.create(P, "other.dev", PW, "Other vendor", EnumSet.of(Role.PARTNER_DEV), "test").principal();
        rev1 = users.create(P, "p.reviewer1", PW, "Reviewer 1", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        rev2 = users.create(P, "p.reviewer2", PW, "Reviewer 2", EnumSet.of(Role.REGULATORY), "test").principal();
        soc = users.create(P, "p.soc", PW, "SOC", EnumSet.of(Role.SECURITY_ANALYST), "test").principal();
        tadmin = users.create(P, "p.tadmin", PW, "Tenant admin", EnumSet.of(Role.TENANT_ADMIN), "test").principal();
        vendorKey = Signatures.generate();
        Current.as(soc, () -> { drivers.trustPublisher(P, "acme-vendor", Signatures.b64(vendorKey.getPublic().getEncoded()), "user:p.soc"); return null; });
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/api/v3/devices/lookup.json", ex -> {
            String q = ex.getRequestURI().getQuery();
            if (q.contains("di=00000000000000")) { reply(ex, 404, "{\"error\":\"not found\"}"); return; }
            reply(ex, 200, "{\"gudid\":{\"device\":{\"brandName\":\"" + gudidBrand + "\",\"versionModelNumber\":\"EasyOne Air\",\"companyName\":\"ndd Medizintechnik AG\","
                    + "\"deviceDescription\":\"Spirometer\",\"deviceRecordStatus\":\"Published\",\"devicePublishDate\":\"2020-01-01\","
                    + "\"productCodes\":{\"fdaProductCode\":[{\"productCode\":\"BZG\",\"productCodeName\":\"Spirometer, diagnostic\"}]},"
                    + "\"gmdnTerms\":{\"gmdn\":{\"gmdnPTName\":\"Spirometer, diagnostic\"}},"
                    + "\"identifiers\":{\"identifier\":[{\"deviceId\":\"07640131990011\",\"deviceIdType\":\"Primary\"}]}}}}");
        });
        stub.createContext("/redirect", ex -> { ex.getResponseHeaders().add("Location", "http://169.254.169.254/"); reply(ex, 302, ""); });
        stub.createContext("/eudamed/devices", ex -> reply(ex, 200, "{\"content\":[{\"primaryDi\":\"04046963123459\",\"tradeName\":\"Spirometer X\",\"manufacturerName\":\"ACME GmbH\","
                + "\"reference\":\"SPX-1\",\"riskClass\":{\"code\":\"class-iia\"},\"deviceStatusType\":{\"code\":\"on-the-market\"}},{\"primaryDi\":\"<bad>\",\"tradeName\":\"x\"}],\"totalElements\":2}"));
        stub.createContext("/openai/deployments/gpt-4o/chat/completions", ex -> {
            llmCalls.incrementAndGet();
            stubBodies.put("llm", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            stubHeaders.put("api-key", String.valueOf(ex.getRequestHeaders().getFirst("api-key")));
            stubHeaders.put("authorization", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            reply(ex, 200, "{\"model\":\"gpt-4o-2024-08-06\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"FEV1 is sent in GDT field 8410 with the value in field 8420 [1].\"}}],\"usage\":{\"prompt_tokens\":321,\"completion_tokens\":17}}");
        });
        stub.createContext("/metadata/identity/oauth2/token", ex -> {
            stubHeaders.put("imds-metadata", String.valueOf(ex.getRequestHeaders().getFirst("Metadata")));
            reply(ex, 200, "{\"access_token\":\"mi-token-xyz\",\"expires_on\":\"" + (java.time.Instant.now().getEpochSecond() + 3600) + "\",\"resource\":\"https://cognitiveservices.azure.com\"}");
        });
        stub.start();
    }

    @AfterAll
    void stop() { if (stub != null) stub.stop(0); }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) ex.getResponseBody().write(b);
        ex.close();
    }

    String base() { return "http://127.0.0.1:" + stub.getAddress().getPort(); }

    ObjectNode signedPackage(String packageId, String modelId, KeyPair key) {
        ObjectNode pkg = (ObjectNode) drivers.require(P, "spiro.ndd.easyone-gdt@1.0.0").json().deepCopy();
        ObjectNode m = pkg.withObject("manifest");
        m.put("packageId", packageId).put("version", "1.0.0").put("name", "ACME spirometer GDT (partner)");
        m.putArray("models").add(modelId);
        ObjectNode driver = Json.obj();
        driver.set("package", pkg);
        driver.put("signature", Signatures.sign(key.getPrivate(), pkg));
        return driver;
    }

    ObjectNode submissionBody(String modelId, ObjectNode driver) {
        ObjectNode b = Json.obj().put("manufacturer", "ndd Medizintechnik").put("model", "EasyOne Air (ACME driver)").put("modelId", modelId).put("udiDi", "07640131990011")
                .put("description", "Partner-certified GDT driver");
        b.putArray("options").add("P2");
        b.putObject("interfaceSpec").put("name", "ACME GDT interface v3").put("contentType", "text/plain")
                .put("text", "GDT record 6310 carries the spirometry result.\n\nThe FEV1 value is transmitted in GDT field 8410 with the result in field 8420 and the unit in 8421.\n\n"
                        + "Questions: support@acme.example MRN: 4455667788 is an example subject identifier.");
        ObjectNode sbom = b.putObject("sbom").put("bomFormat", "CycloneDX").put("specVersion", "1.5");
        sbom.putArray("components").add(Json.obj().put("name", "openssl").put("version", "3.0.13")).add(Json.obj().put("name", "zlib").put("version", "1.3.1"));
        b.putObject("mds2").put("manufacturer", "ndd").put("deviceModel", "EasyOne Air").put("endOfSupport", "2032-12-31").put("ALOF-1", "Yes");
        b.set("driver", driver);
        return b;
    }

    String provision(IntiPrincipal who, String driver) {
        String actor = Current.as(who, Current::actor);
        return Current.as(who, () -> {
            ObjectNode c = configs.create(P, "Partner " + driver, "IMD-001", Json.obj().put("site", "Partner lab").put("station", "PTN-SPIRO-1"), actor);
            String id = c.get("id").asString();
            configs.saveStep(P, id, 1, Json.obj().put("driver", driver), actor);
            for (int s = 2; s <= 6; s++) {
                configs.recommendations(P, id, s);
                configs.acceptAll(P, id, s, actor);
                ObjectNode body = Json.obj();
                if (s == 3) body.put("endpointId", probes.demoGateway(P).endpointId());
                configs.saveStep(P, id, s, body, actor);
            }
            return id;
        });
    }

    // ------------------------------------------------------------------ IM-E02-BL-006 / IM-E07-BL-005
    @Test
    @Order(1)
    void vendorSubmissionIsSignatureCheckedStoredAndOnlyVisibleToItsOwner() {
        // unsigned / foreign-signed package → refused by the unchanged publish path
        ObjectNode forged = signedPackage(DRIVER_ID, "IMD-001", Signatures.generate());
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.submit(P, submissionBody("IMD-001", forged), "user:acme.dev"))).hasMessageContaining("signature");
        // only PARTNER_DEV may submit
        assertThatThrownBy(() -> Current.as(rev1, () -> submissions.submit(P, submissionBody("IMD-001", signedPackage(DRIVER_ID, "IMD-001", vendorKey)), "user:p.reviewer1")))
                .hasMessageContaining("PARTNER_DEV");
        // the driver must list the submitted model
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.submit(P, submissionBody("IMD-002", signedPackage(DRIVER_ID, "IMD-001", vendorKey)), "user:acme.dev")))
                .hasMessageContaining("manifest.models");
        ObjectNode s = Current.as(vendor, () -> submissions.submit(P, submissionBody("IMD-001", signedPackage(DRIVER_ID, "IMD-001", vendorKey)), "user:acme.dev"));
        submissionId = s.get("id").asString();
        driverRef = s.get("driverRef").asString();
        assertThat(s.get("status").asString()).isEqualTo("SUBMITTED");
        assertThat(driverRef).isEqualTo(DRIVER_ID + "@1.0.0");
        assertThat(drivers.require(P, driverRef).channel()).isEqualTo("CANDIDATE");
        assertThat(drivers.require(P, driverRef).publisher()).isEqualTo("acme-vendor");
        assertThat(s.at("/sbom/components").asInt()).isEqualTo(2);
        assertThat(s.at("/interfaceSpec/sha256").asString()).hasSize(64);
        assertThat(s.at("/interfaceSpec/docId").asString()).startsWith("spec-");
        assertThat(Current.as(vendor, () -> submissions.list(P))).extracting(x -> x.get("id").asString()).contains(submissionId);
        assertThat(Current.as(vendor2, () -> submissions.list(P))).isEmpty();
        assertThatThrownBy(() -> Current.as(vendor2, () -> submissions.get(P, submissionId))).isInstanceOf(ApiException.class).hasMessageContaining("not found");
        assertThat(Current.as(vendor, () -> submissions.interfaceSpec(P, submissionId)).get("contentBase64").asString()).isNotBlank();
        // input limits: bad PDF, oversize spec
        ObjectNode pdf = submissionBody("IMD-001", signedPackage("partner.acme.other", "IMD-001", vendorKey));
        pdf.putObject("interfaceSpec").put("name", "x.pdf").put("contentType", "application/pdf").put("contentBase64", Base64.getEncoder().encodeToString("not a pdf".getBytes()));
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.submit(P, pdf, "user:acme.dev"))).hasMessageContaining("not a PDF");
        ObjectNode big = submissionBody("IMD-001", signedPackage("partner.acme.other", "IMD-001", vendorKey));
        big.putObject("interfaceSpec").put("name", "big.txt").put("contentType", "text/plain").put("contentBase64", Base64.getEncoder().encodeToString(new byte[5 * 1024 * 1024 + 1]));
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.submit(P, big, "user:acme.dev"))).hasMessageContaining("5 MB");
        ObjectNode badSbom = submissionBody("IMD-001", signedPackage("partner.acme.other", "IMD-001", vendorKey));
        badSbom.putObject("sbom").put("hello", "world");
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.submit(P, badSbom, "user:acme.dev"))).hasMessageContaining("CycloneDX");
        assertThat(audit.byTarget(P, "partner-submission/" + submissionId)).anySatisfy(a -> assertThat(a.get("action")).isEqualTo("partner.submission.create"));
    }

    @Test
    @Order(2)
    void reviewHarnessAndFourEyesCertificationGrantABadgeVisibleInTheCatalogue() {
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.startReview(P, submissionId, "user:acme.dev"))).hasMessageContaining("Requires one of");
        assertThatThrownBy(() -> Current.as(rev1, () -> submissions.certify(P, submissionId, "too early", "user:p.reviewer1"))).hasMessageContaining("expected HARNESS_PASSED");
        ObjectNode r = Current.as(rev1, () -> submissions.startReview(P, submissionId, "user:p.reviewer1"));
        assertThat(r.get("status").asString()).isEqualTo("IN_REVIEW");
        ObjectNode posture = catalogue.posture(P, "IMD-001");
        assertThat(posture.get("status").asString()).isEqualTo("ACCEPTABLE");
        assertThat(posture.get("source").asString()).isEqualTo("PARTNER_SUBMISSION");
        configId = provision(rev1, driverRef);
        ObjectNode h = Current.as(rev1, () -> submissions.recordHarness(P, submissionId, Json.obj().put("configId", configId), "user:p.reviewer1"));
        ObjectNode run = store.require(P, "run", h.path("lastHarnessRun").asString());
        assertThat(h.get("status").asString()).as(Json.pretty(run.get("results"))).isEqualTo("HARNESS_PASSED");
        runId = h.get("harnessRunId").asString();
        assertThat(h.get("evidenceSeal").asString()).isEqualTo(run.get("seal").asString()).hasSize(64);
        // four-eyes: the reviewer who recorded the harness result cannot certify; vendors cannot certify at all
        assertThatThrownBy(() -> Current.as(rev1, () -> submissions.certify(P, submissionId, "self", "user:p.reviewer1"))).hasMessageContaining("four-eyes");
        assertThatThrownBy(() -> Current.as(vendor, () -> submissions.certify(P, submissionId, "self", "user:acme.dev"))).hasMessageContaining("Requires one of");
        assertThat(audit.byTarget(P, "partner-submission/" + submissionId)).anySatisfy(a -> assertThat(a.get("action")).isEqualTo("partner.submission.certify.refused"));
        ObjectNode c = Current.as(rev2, () -> submissions.certify(P, submissionId, "programme approved", "user:p.reviewer2"));
        assertThat(c.get("status").asString()).isEqualTo("CERTIFIED");
        assertThat(drivers.require(P, driverRef).channel()).isEqualTo("CERTIFIED");
        List<ObjectNode> active = badges.list(P, "ACTIVE");
        assertThat(active).hasSize(1);
        ObjectNode badge = active.get(0);
        assertThat(badge.get("level").asString()).isEqualTo("GOLD");
        assertThat(badge.get("runId").asString()).isEqualTo(runId);
        assertThat(badge.get("evidenceSeal").asString()).isEqualTo(run.get("seal").asString());
        assertThat(badge.get("harnessVersion").asString()).contains("XT-01").contains("XT-17");
        assertThat(badge.get("grantedAt").asString()).isNotBlank();
        // visible in catalogue search results and on the model
        ObjectNode hit = catalogue.search(P, "IMD-001", null, null, null, null, 5).stream().filter(m -> m.get("id").asString().equals("IMD-001")).findFirst().orElseThrow();
        assertThat(hit.path("badges").get(0).path("evidenceSeal").asString()).isEqualTo(badge.get("evidenceSeal").asString());
        assertThat(catalogue.model(P, "IMD-001").path("badges")).hasSize(1);
        // other tenants never see it
        assertThat(catalogue.model("t-other", "IMD-001").path("badges")).isEmpty();
        List<String> trail = audit.byTarget(P, "partner-submission/" + submissionId).stream().map(a -> String.valueOf(a.get("action"))).toList();
        assertThat(trail).contains("partner.submission.create", "partner.submission.review", "partner.submission.harness", "partner.submission.certify");
    }

    @Test
    @Order(3)
    void badgeIsRevokedByAFailingRegressionRunAndByDriverWithdrawal() {
        String badgeId = badges.list(P, "ACTIVE").get(0).get("id").asString();
        // a later regression run of the same driver fails → automatic revocation (run.completed event)
        String failed = "run-regress1";
        store.put(P, "run", failed, Json.obj().put("id", failed).put("driver", driverRef).put("configId", configId).put("status", "FAILED"), "FAILED", configId);
        events.publish(P, "run.completed", Json.obj().put("run", failed).put("config", configId).put("status", "FAILED"));
        ObjectNode revoked = store.require(P, "badge", badgeId);
        assertThat(revoked.get("status").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("revokedByRun").asString()).isEqualTo(failed);
        assertThat(catalogue.model(P, "IMD-001").path("badges")).isEmpty();
        // re-granted on a passing run, then the driver is withdrawn → revoked again
        ObjectNode sub = store.require(P, "partner-submission", submissionId);
        Current.as(rev2, () -> badges.grant(P, sub, store.require(P, "run", runId), catalogue.posture(P, "IMD-001"), "user:p.reviewer2"));
        assertThat(badges.list(P, "ACTIVE")).hasSize(1);
        Current.as(rev1, () -> { drivers.withdraw(P, driverRef, "vendor recall", "user:p.reviewer1"); return null; });
        assertThat(badges.list(P, "ACTIVE")).isEmpty();
        assertThat(badges.list(P, "REVOKED")).anySatisfy(b -> assertThat(b.get("revocationReason").asString()).isEqualTo("driver withdrawn"));
        assertThat(drivers.all(P)).noneMatch(d -> d.ref().equals(driverRef));
        assertThat(drivers.require(P, driverRef).channel()).isEqualTo("WITHDRAWN");
    }

    @Test
    @Order(4)
    void newModelSubmissionWithPdfSpecRegistersATenantModelAndCanBeRejected() {
        byte[] pdf = "%PDF-1.7\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        ObjectNode body = submissionBody("PTN-ACME-X2", signedPackage("partner.acme.x2", "PTN-ACME-X2", vendorKey));
        body.putObject("interfaceSpec").put("name", "x2-interface.pdf").put("contentType", "application/pdf").put("contentBase64", Base64.getEncoder().encodeToString(pdf));
        ObjectNode s = Current.as(vendor, () -> submissions.submit(P, body, "user:acme.dev"));
        assertThat(s.get("newModel").asBoolean()).isTrue();
        assertThat(catalogue.model(P, "PTN-ACME-X2").get("source").asString()).isEqualTo("PARTNER_SUBMISSION");
        String id = s.get("id").asString();
        assertThatThrownBy(() -> Current.as(rev1, () -> submissions.reject(P, id, " ", "user:p.reviewer1"))).hasMessageContaining("reason");
        ObjectNode r = Current.as(rev1, () -> submissions.reject(P, id, "interface specification incomplete", "user:p.reviewer1"));
        assertThat(r.get("status").asString()).isEqualTo("REJECTED");
        assertThat(drivers.require(P, "partner.acme.x2@1.0.0").channel()).isEqualTo("WITHDRAWN");
        assertThat(r.path("history").size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ IM-E02-BL-003
    UdiFeedService udi(String allowedHosts, boolean loopbackHttp, String gudidBase) {
        return new UdiFeedService(catalogue, store, audit, new UdiFeedService.Settings(true, gudidBase, true, base() + "/eudamed",
                "/devices?primaryDi={di}", "/devices?tradeName={q}", "/content",
                "di:/primaryDi,tradeName:/tradeName,manufacturer:/manufacturerName,model:/reference,riskClass:/riskClass/code,status:/deviceStatusType/code",
                allowedHosts, loopbackHttp));
    }

    @Test
    @Order(10)
    void gudidConnectorIsDisabledByDefaultAndDetectsChangesForReviewerApproval() {
        assertThatThrownBy(() -> udiDefault.gudidLookup(P, "07640131990011", "IMD-001", "user:p.reviewer1")).hasMessageContaining("disabled");
        assertThatThrownBy(() -> udiDefault.eudamedSearch(P, "07640131990011", null, null, "user:p.reviewer1")).hasMessageContaining("disabled");
        UdiFeedService u = udi("127.0.0.1", true, base());
        assertThatThrownBy(() -> u.gudidLookup(P, "07640131990011' or 1=1", null, "user:p.reviewer1")).hasMessageContaining("device identifier");
        ObjectNode first = u.gudidLookup(P, "07640131990011", "IMD-001", "user:p.reviewer1");
        assertThat(first.get("queued").asBoolean()).isTrue();
        assertThat(first.get("change").asString()).isEqualTo("NEW");
        assertThat(first.at("/record/productCodes/0").asString()).isEqualTo("BZG");
        // same content again → not queued twice
        assertThat(u.gudidLookup(P, "07640131990011", "IMD-001", "user:p.reviewer1").get("change").asString()).isEqualTo("ALREADY_QUEUED");
        assertThat(catalogue.model(P, "IMD-001").path("udi")).isEmpty();
        ObjectNode chg = store.require(P, "feed-change", first.get("changeId").asString());
        assertThat(chg.get("type").asString()).isEqualTo("UDI_RECORD");
        Current.as(rev2, () -> feeds.review(P, chg.get("id").asString(), true, "user:p.reviewer2"));
        assertThat(catalogue.model(P, "IMD-001").path("udi")).hasSize(1);
        assertThat(u.gudidLookup(P, "07640131990011", "IMD-001", "user:p.reviewer1").get("change").asString()).isEqualTo("UNCHANGED");
        // the register changes the record → content hash differs → queued with the previous snapshot for the reviewer
        gudidBrand = "EasyOne Air 2";
        ObjectNode changed = u.gudidLookup(P, "07640131990011", "IMD-001", "user:p.reviewer1");
        assertThat(changed.get("change").asString()).isEqualTo("CHANGED");
        assertThat(changed.get("previousHash").asString()).isEqualTo(first.get("hash").asString());
        ObjectNode chg2 = store.require(P, "feed-change", changed.get("changeId").asString());
        assertThat(chg2.at("/change/previous/brandName").asString()).isEqualTo("EasyOne Air");
        assertThat(chg2.at("/change/productCodeMatch").asBoolean()).isTrue();
        assertThat(u.gudidLookup(P, "00000000000000", null, "user:p.reviewer1").get("found").asBoolean()).isFalse();
        // EUDAMED: configurable JSON shape, invalid DIs in results are skipped
        ObjectNode e = u.eudamedSearch(P, null, "Spirometer", null, "user:p.reviewer1");
        assertThat(e.get("results").asInt()).isEqualTo(1);
        assertThat(e.at("/items/0/record/riskClass").asString()).isEqualTo("class-iia");
        assertThat(e.get("queued").asInt()).isEqualTo(1);
    }

    @Test
    @Order(11)
    void egressGuardEnforcesHostAllowListHttpsAndRefusesRedirects() {
        assertThatThrownBy(() -> udi("accessgudid.nlm.nih.gov", true, base()).gudidLookup(P, "07640131990011", null, "u")).hasMessageContaining("allow-list");
        assertThatThrownBy(() -> udi("127.0.0.1", false, base()).gudidLookup(P, "07640131990011", null, "u")).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> udi("127.0.0.1", true, base() + "/redirect").gudidLookup(P, "07640131990011", null, "u")).hasMessageContaining("redirect refused");
        // allow-listed name that resolves to a private address is refused even over HTTPS
        assertThatThrownBy(() -> udi("localhost", false, "https://localhost:1").gudidLookup(P, "07640131990011", null, "u")).hasMessageContaining("private or local");
    }

    // ------------------------------------------------------------------ IM-E03-BL-006
    ExplanationService gateway(String allowedHosts, String auth, int rpm) {
        return new ExplanationService(docs, secrets, limiter, audit, tenants, new ExplanationService.Settings(true, base(), "gpt-4o", "2024-06-01", auth,
                "secret://azure-openai-key", allowedHosts, 300, rpm, true, base() + "/metadata/identity/oauth2/token?api-version=2018-02-01&resource=https://cognitiveservices.azure.com"));
    }

    @Test
    @Order(20)
    void llmGatewayIsOffByDefaultRedactsPhiEnforcesResidencyAndAuditsHashesOnly() {
        assertThat(explanationDefault.policy(P).get("globalEnabled").asBoolean()).isFalse();
        assertThatThrownBy(() -> explanationDefault.explain(P, "How is FEV1 transmitted?", "user:p.reviewer1")).hasMessageContaining("disabled");
        ExplanationService g = gateway("127.0.0.1", "api-key", 30);
        assertThatThrownBy(() -> g.explain(P, "How is FEV1 transmitted?", "user:p.reviewer1")).hasMessageContaining("not enabled for this tenant");
        Current.as(tadmin, () -> { g.setTenantEnabled(P, true, "user:p.tadmin"); return null; });
        assertThatThrownBy(() -> g.explain(P, "How is FEV1 transmitted?", "user:p.reviewer1")).hasMessageContaining("azure-openai-key");
        secrets.put(P, "azure-openai-key", "test-key-0123456789", "user:p.soc");
        // no citations → no model call
        ObjectNode none = g.explain(P, "zzqx wwvy", "user:p.reviewer1");
        assertThat(none.get("status").asString()).isEqualTo("NO_CITATIONS");
        assertThat(llmCalls.get()).isZero();
        ObjectNode r = g.explain(P, "Which field transmits FEV1 for patient MRN 99887766, phone 555-123-4567?", "user:p.reviewer1");
        assertThat(r.get("label").asString()).isEqualTo("AI-generated explanation – UNVERIFIED, citations required");
        assertThat(r.get("status").asString()).isEqualTo("OK");
        assertThat(r.get("citations").size()).isPositive();
        assertThat(r.get("explanation").asString()).contains("[1]");
        assertThat(r.get("advisoryOnly").asBoolean()).isTrue();
        String sent = stubBodies.get("llm");
        assertThat(sent).doesNotContain("99887766").doesNotContain("555-123-4567").doesNotContain("support@acme.example").doesNotContain("4455667788")
                .contains("[REDACTED]").contains("8410").contains("\"max_tokens\":300");
        assertThat(stubHeaders.get("api-key")).isEqualTo("test-key-0123456789");
        List<Map<String, Object>> trail = audit.recent(P, 20);
        Map<String, Object> rec = trail.stream().filter(a -> "llm.explain".equals(a.get("action"))).findFirst().orElseThrow();
        String detail = String.valueOf(rec.get("detail"));
        assertThat(detail).contains(r.get("promptHash").asString()).contains(Hashing.sha256(r.get("explanation").asString()))
                .doesNotContain("FEV1").doesNotContain("8410").doesNotContain("test-key");
        // data residency: endpoint host not on the allow-list → refused before any call or credential lookup
        int before = llmCalls.get();
        assertThatThrownBy(() -> gateway("myres.openai.azure.com", "api-key", 30).explain(P, "Which field transmits FEV1?", "u")).hasMessageContaining("allow-list");
        assertThat(llmCalls.get()).isEqualTo(before);
        assertThatThrownBy(() -> g.explain(P, "x".repeat(1001), "u")).hasMessageContaining("1000");
    }

    @Test
    @Order(21)
    void llmGatewayUsesManagedIdentityAndRateLimits() {
        ExplanationService g = gateway("127.0.0.1", "managed-identity", 1);
        ObjectNode r = g.explain(P, "Which field transmits FEV1?", "user:p.reviewer1");
        assertThat(r.get("status").asString()).isEqualTo("OK");
        assertThat(stubHeaders.get("authorization")).isEqualTo("Bearer mi-token-xyz");
        assertThat(stubHeaders.get("imds-metadata")).isEqualTo("true");
        assertThat(g.policy(P).get("auth").asString()).isEqualTo("managed-identity");
        assertThatThrownBy(() -> g.explain(P, "Which field transmits FEV1?", "user:p.reviewer1")).hasMessageContaining("rate limit");
    }

    // ------------------------------------------------------------------ IM-E07-BL-004
    @Test
    @Order(30)
    void openApiDocumentIsGeneratedFromTheDeployedMappingsAndServedPublicly() throws Exception {
        String url = "https://localhost:" + env.getProperty("local.server.port") + "/api/public/openapi.json";
        HttpClient c = HttpClient.newBuilder().sslContext(LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null)).build();
        HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        JsonNode doc = Json.parse(r.body());
        assertThat(doc.get("openapi").asString()).isEqualTo("3.1.0");
        JsonNode paths = doc.get("paths");
        assertThat(paths.has("/fhir/r4/Observation")).isTrue();
        assertThat(paths.has("/fhir/r4/Observation/{id}")).isTrue();
        assertThat(paths.has("/api/intelligence/validate")).isTrue();
        assertThat(paths.has("/api/intelligence/explain")).isTrue();
        assertThat(paths.has("/api/partner/submissions")).isTrue();
        assertThat(paths.has("/api/partner/badges")).isTrue();
        assertThat(paths.has("/oauth2/token")).isTrue();
        assertThat(paths.has("/api/v1/harness/runs")).isTrue();
        assertThat(paths.has("/api/console/configs")).as("console-internal APIs are not published").isFalse();
        assertThat(paths.at("/~1fhir~1r4~1Observation/get/security/0").has("oauth2")).isTrue();
        assertThat(paths.at("/~1fhir~1r4~1Observation/get/parameters").toString()).contains("\"subject\"").contains("\"_count\"");
        assertThat(paths.at("/~1api~1partner~1submissions~1{id}~1certify/post/parameters/0/name").asString()).isEqualTo("id");
        assertThat(paths.at("/~1oauth2~1token/post/requestBody/content").has("application/x-www-form-urlencoded")).isTrue();
        assertThat(doc.at("/components/securitySchemes/oauth2/flows/clientCredentials/tokenUrl").asString()).isEqualTo("/oauth2/token");
    }
}
