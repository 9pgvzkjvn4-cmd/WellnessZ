package io.intimed;

import io.intimed.commercial.BenchmarkService;
import io.intimed.commercial.BillingService;
import io.intimed.commercial.BrandingService;
import io.intimed.commercial.MonthlyReportService;
import io.intimed.commercial.TenantHealthService;
import io.intimed.commercial.TrialService;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.console.intelligence.KnowledgeBase;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.events.EventBus;
import io.intimed.harness.HarnessService;
import io.intimed.harness.ProbeIdentity;
import io.intimed.operations.RegressionService;
import io.intimed.operations.ShadowReviewService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.UserService;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import io.intimed.web.CommercialController;
import io.intimed.web.OperationsController;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Operations & commercial backlog (IM-E04-BL-007 regression suites, IM-E04-BL-005 shadow sign-off, IM-E09-BL-003 monthly
 * report, IM-E09-BL-005 health score, IM-E09-BL-002 billing export/reconciliation, IM-E09-BL-001 trials,
 * IM-E09-BL-006 branding, IM-E02-BL-007 / IM-E09-BL-004 benchmarks) on a real running platform.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "intimed.device.mllp-port=0", "intimed.device.ingress-port=0", "management.server.port=0", "intimed.demo.enabled=false",
        "intimed.regression.enabled=false", "intimed.trials.enabled=false", "intimed.benchmarks.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationsCommercialTest {
    static final String T = "oc-main", PW = "Integration-Test-2026";

    static {
        try {
            Path home = Files.createTempDirectory("intimed-oc");
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
    @Autowired EventBus events;
    @Autowired SecretStore secrets;
    @Autowired KnowledgeBase kb;
    @Autowired RegressionService regression;
    @Autowired ShadowReviewService shadow;
    @Autowired MonthlyReportService reports;
    @Autowired TenantHealthService health;
    @Autowired BillingService billing;
    @Autowired TrialService trials;
    @Autowired io.intimed.web.SecurityController securityController;
    @Autowired io.intimed.engine.pipeline.Pipeline pipeline;
    @Autowired BrandingService branding;
    @Autowired BenchmarkService benchmarks;
    @Autowired OperationsController opsApi;
    @Autowired CommercialController commercialApi;

    IntiPrincipal admin, approver, owner1, owner2, nurse, tadmin;
    String cfgId;
    /** Closed month for billing fixtures. */
    final String M = YearMonth.now(ZoneOffset.UTC).minusMonths(1).toString();

    @BeforeAll
    void setup() {
        tenants.create(T, "Ops & commercial", "eu-west", "ENTERPRISE", null, "test");
        admin = users.create(T, "oc.admin", PW, "Admin", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        approver = users.create(T, "oc.approver", PW, "Approver", EnumSet.of(Role.DEVICE_ADMIN), "test").principal();
        owner1 = users.create(T, "oc.owner1", PW, "Owner 1", EnumSet.of(Role.TEMPLATE_OWNER), "test").principal();
        owner2 = users.create(T, "oc.owner2", PW, "Owner 2", EnumSet.of(Role.TEMPLATE_OWNER), "test").principal();
        nurse = users.create(T, "oc.nurse", PW, "Nurse", EnumSet.of(Role.CLINICIAN), "test").principal();
        tadmin = users.create(T, "oc.tadmin", PW, "Tenant admin", EnumSet.of(Role.TENANT_ADMIN), "test").principal();
        IntiPrincipal soc = users.create(T, "oc.soc", PW, "SOC", EnumSet.of(Role.SECURITY_ANALYST), "test").principal();
        Current.as(soc, () -> new io.intimed.catalogue.CatalogueService(drivers, store, audit).recordPosture(T, "IMD-037",
                Json.obj().put("mds2", "RECEIVED").put("sbom", "RECEIVED").put("knownVulnerabilities", 0), "user:oc.soc"));
        // provision → test → four-eyes → publish a blood-pressure cuff configuration
        cfgId = Current.as(admin, () -> {
            ObjectNode c = configs.create(T, "OC BP", "IMD-037", Json.obj().put("site", "Clinic").put("station", "OC-BP"), "user:oc.admin");
            String id = c.get("id").asString();
            configs.saveStep(T, id, 1, Json.obj().put("driver", "bp.and.ua651-ble@1.0.0"), "user:oc.admin");
            for (int s = 2; s <= 6; s++) {
                configs.recommendations(T, id, s);
                configs.acceptAll(T, id, s, "user:oc.admin");
                ObjectNode body = Json.obj();
                if (s == 3) body.put("endpointId", probes.demoGateway(T).endpointId());
                configs.saveStep(T, id, s, body, "user:oc.admin");
            }
            ObjectNode bp = configs.get(T, id);
            configs.saveStep(T, id, 5, Json.obj().set("bindings", Json.arr().add(bp.get("bindings").get(0))), "user:oc.admin");
            ObjectNode run = harness.run(T, id, "user:oc.admin");
            assertThat(run.get("status").asString()).as(Json.pretty(run.get("results"))).isEqualTo("PASSED");
            configs.submitForCertification(T, id, "user:oc.admin");
            return id;
        });
        Current.as(approver, () -> configs.approve(T, cfgId, "ok", "user:oc.approver"));
        assertThat(Current.as(admin, () -> configs.publish(T, cfgId, "DEV", "user:oc.admin")).get("status").asString()).isEqualTo("PUBLISHED");
    }

    // ---------------------------------------------------------------- IM-E04-BL-007
    @Test
    @Order(1)
    void scheduledRegressionStoresRunsTrendsAndRaisesFailures() {
        assertThat(regression.enabled()).isFalse(); // the nightly cron is disabled in tests; the job is triggered directly
        var summary = regression.runAllTenants("SCHEDULED");
        assertThat(summary).containsEntry(T, 1);
        List<ObjectNode> docs = store.listByRef(T, RegressionService.KIND, cfgId);
        assertThat(docs).hasSize(1);
        ObjectNode d = docs.get(0);
        assertThat(d.get("status").asString()).as(Json.pretty(d)).isEqualTo("PASSED");
        assertThat(d.get("passed").asInt()).isEqualTo(d.get("total").asInt()).isPositive();
        assertThat(d.get("runId").asString()).startsWith("run-");
        assertThat(d.get("startedAt").asString()).isNotBlank();
        assertThat(audit.byTarget(T, "config/" + cfgId)).anySatisfy(a -> {
            assertThat(a.get("action")).isEqualTo("regression.passed");
            assertThat(a.get("actor")).isEqualTo("system:regression");
        });

        // a published configuration that can no longer be exercised is a failing regression → event + audit
        ObjectNode broken = (ObjectNode) configs.get(T, cfgId).deepCopy();
        broken.put("id", "cfg-broken01").put("driver", "missing.driver@9.9.9");
        store.put(T, "config", "cfg-broken01", broken, "PUBLISHED", broken.get("modelId").asString());
        List<ObjectNode> second = Current.as(admin, () -> {
            Current.require(Role.DEVICE_ADMIN);
            return regression.runTenant(T, "MANUAL", "user:oc.admin");
        });
        assertThat(second).extracting(r -> r.get("status").asString()).containsExactlyInAnyOrder("PASSED", "ERROR");
        assertThat(events.recent(T, 30)).anySatisfy(e -> {
            assertThat(e.get("type").asString()).isEqualTo("regression.failed");
            assertThat(e.at("/data/config").asString()).isEqualTo("cfg-broken01");
        });
        assertThat(audit.byTarget(T, "config/cfg-broken01")).anySatisfy(a -> assertThat(a.get("action")).isEqualTo("regression.failed"));
        assertThat(audit.byTarget(T, "tenant/" + T)).anySatisfy(a -> assertThat(a.get("action")).isEqualTo("regression.trigger"));

        ObjectNode trends = regression.trends(T, cfgId);
        assertThat(trends.get("configs")).hasSize(1);
        ObjectNode c = (ObjectNode) trends.get("configs").get(0);
        assertThat(c.get("runs").asInt()).isEqualTo(2);
        assertThat(c.get("passRatePct").asDouble()).isEqualTo(100.0);
        assertThat(c.get("points")).hasSize(2);
        assertThat(c.has("latestP95Ms")).isTrue();
        assertThat(c.get("trend").asString()).isEqualTo("STABLE");
        ObjectNode all = regression.trends(T, null);
        assertThat(all.get("runs").asInt()).isEqualTo(3);
        assertThat(all.get("passRatePct").asDouble()).isEqualTo(66.7);
        // role checks on the API
        assertThatThrownBy(() -> Current.as(nurse, () -> opsApi.runNow())).isInstanceOf(ApiException.class).hasMessageContaining("Requires one of");
        assertThat(Current.as(admin, () -> opsApi.trends(cfgId)).get("configs")).hasSize(1);
        // keep the broken configuration out of the later checks
        broken.put("status", "DECOMMISSIONED");
        store.put(T, "config", "cfg-broken01", broken, "DECOMMISSIONED", broken.get("modelId").asString());
    }

    // ---------------------------------------------------------------- IM-E04-BL-005
    @Test
    @Order(2)
    void shadowDiscrepancyReportAndFourEyesSignOff() {
        ObjectNode c = (ObjectNode) configs.get(T, cfgId).deepCopy();
        c.put("id", "cfg-shadow01").put("createdBy", "user:oc.owner1");
        c.putObject("shadow").put("until", Instant.now().plus(7, ChronoUnit.DAYS).toString()).put("signedOff", false);
        store.put(T, "config", "cfg-shadow01", c, "PUBLISHED", c.get("modelId").asString());
        shadowInstance("ti-shadow-a", "SN-A", 120, 80);
        shadowInstance("ti-shadow-b", "SN-A", 130, 85);

        assertThatThrownBy(() -> shadow.report(T, cfgId + "x", null)).isInstanceOf(ApiException.class);
        // manual reference values (clinician / template owner)
        Current.as(nurse, () -> opsApi.manual("cfg-shadow01", Json.obj().put("instanceId", "ti-shadow-a").put("field", "systolic").put("value", 121)));
        Current.as(owner1, () -> opsApi.manual("cfg-shadow01", Json.obj().put("instanceId", "ti-shadow-a").put("field", "diastolic").put("value", "90")));
        Current.as(nurse, () -> opsApi.manual("cfg-shadow01", Json.obj().put("instanceId", "ti-shadow-b").put("field", "systolic").put("value", 130).put("unit", "mm[Hg]")));
        assertThatThrownBy(() -> Current.as(nurse, () -> opsApi.manual("cfg-shadow01", Json.obj().put("instanceId", "ti-shadow-b").put("field", "nope").put("value", 1))))
                .isInstanceOf(ApiException.class).hasMessageContaining("not captured");
        assertThatThrownBy(() -> Current.as(admin, () -> opsApi.manual("cfg-shadow01", Json.obj().put("instanceId", "ti-shadow-b").put("field", "systolic").put("value", 1))))
                .isInstanceOf(ApiException.class).hasMessageContaining("Requires one of");
        assertThat(store.rawStored(T, ShadowReviewService.REF_KIND, Hashing.stableId("shr", "ti-shadow-a/systolic")).orElseThrow()).startsWith("enc:v1:");

        ObjectNode r = Current.as(owner2, () -> opsApi.report("cfg-shadow01", null));
        assertThat(r.get("pairs").asLong()).isEqualTo(3);
        assertThat(r.get("agreed").asLong()).isEqualTo(2);
        assertThat(r.get("agreementPct").asDouble()).isEqualTo(66.7);
        assertThat(r.get("readyForSignOff").asBoolean()).isFalse();
        ObjectNode sys = row(r, "systolic"), dia = row(r, "diastolic");
        assertThat(sys.get("device").asString()).isEqualTo("SN-A");
        assertThat(sys.get("unit").asString()).isEqualTo("mm[Hg]");
        assertThat(sys.get("pairs").asLong()).isEqualTo(2);
        assertThat(sys.get("agreementPct").asDouble()).isEqualTo(100.0);
        assertThat(sys.get("meanAbsDiff").asDouble()).isEqualTo(0.5);
        assertThat(sys.get("maxAbsDiff").asDouble()).isEqualTo(1.0);
        assertThat(dia.get("agreementPct").asDouble()).isEqualTo(0.0);
        assertThat(dia.get("meanAbsDiff").asDouble()).isEqualTo(10.0);
        assertThat(Json.write(r)).doesNotContain("SUBJ-OC-1");
        // stricter tolerance: 121 vs 120 no longer agrees
        assertThat(shadow.report(T, "cfg-shadow01", 0.5).get("agreed").asLong()).isEqualTo(1);

        Envelope live = Envelope.live(T, null, "cfg-shadow01", "P6", "x", "local");
        assertThat(Pipeline.shadowActive(configs.get(T, "cfg-shadow01"), live)).isTrue();
        // four-eyes: the creator cannot sign off; a clinician cannot sign off at all
        assertThatThrownBy(() -> Current.as(owner1, () -> opsApi.signOff("cfg-shadow01", Json.obj().put("comment", "self"))))
                .isInstanceOf(ApiException.class).hasMessageContaining("creator");
        assertThatThrownBy(() -> Current.as(nurse, () -> opsApi.signOff("cfg-shadow01", null))).isInstanceOf(ApiException.class).hasMessageContaining("Requires one of");
        ObjectNode signed = Current.as(owner2, () -> opsApi.signOff("cfg-shadow01", Json.obj().put("comment", "agreement acceptable after review")));
        assertThat(signed.at("/shadow/signedOff").asBoolean()).isTrue();
        assertThat(signed.at("/shadow/signedOffBy").asString()).isEqualTo("user:oc.owner2");
        assertThat(signed.at("/shadow/evidence/pairs").asLong()).isEqualTo(3);
        assertThat(Pipeline.shadowActive(configs.get(T, "cfg-shadow01"), live)).isFalse();
        assertThatThrownBy(() -> Current.as(owner2, () -> opsApi.signOff("cfg-shadow01", null))).isInstanceOf(ApiException.class).hasMessageContaining("already");
        assertThat(audit.byTarget(T, "config/cfg-shadow01")).extracting(a -> a.get("action"))
                .contains("shadow.reference.record", "shadow.report", "shadow.signoff.refused", "shadow.signoff");
        c.put("status", "DECOMMISSIONED");
        store.put(T, "config", "cfg-shadow01", configs.get(T, "cfg-shadow01").put("status", "DECOMMISSIONED"), "DECOMMISSIONED", c.get("modelId").asString());
    }

    private void shadowInstance(String id, String serial, int sys, int dia) {
        ObjectNode i = Json.obj().put("id", id).put("templateId", "TPL-02").put("subject", "SUBJ-OC-1").put("configId", "cfg-shadow01").put("deviceSerial", serial)
                .put("mode", "SHADOW").put("effectiveTime", Instant.now().toString());
        i.putObject("fields").set("systolic", Json.obj().put("value", sys).put("unit", "mm[Hg]"));
        i.withObject("fields").set("diastolic", Json.obj().put("value", dia).put("unit", "mm[Hg]"));
        store.put(T, "shadow", id, i, "FILED", "TPL-02");
    }

    private static ObjectNode row(ObjectNode r, String field) {
        for (var x : r.get("rows")) if (field.equals(x.get("field").asString())) return (ObjectNode) x;
        throw new AssertionError("no row " + field);
    }

    // ---------------------------------------------------------------- IM-E09-BL-003
    @Test
    @Order(3)
    void monthlyExecutiveReportHasAllSectionsAndIsPersisted() {
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        ObjectNode r = Current.as(tadmin, () -> commercialApi.monthly(month));
        assertThat(r.get("month").asString()).isEqualTo(month);
        assertThat(r.get("status").asString()).isEqualTo("PROVISIONAL");
        for (String p : List.of("/readings/captureRatePct", "/readings/verifiedByClinician", "/readings/estimatedMinutesSaved", "/dataQuality/dlqRatePct",
                "/dataQuality/unmatchedRatePct", "/dataQuality/heldRatePct", "/calibration/compliancePct", "/fleet/devices", "/fleet/healthyPct", "/regression/passRatePct")) {
            assertThat(r.at(p).isMissingNode()).as(p).isFalse();
        }
        assertThat(r.at("/fleet/devices").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(r.at("/calibration/compliancePct").asDouble()).isEqualTo(100.0);
        assertThat(r.at("/regression/runs").asInt()).isEqualTo(3);
        assertThat(r.at("/metering/harness.runs").asLong()).isPositive();
        assertThat(store.get(T, MonthlyReportService.KIND, month)).isPresent();
        String csv = MonthlyReportService.csv(r);
        assertThat(csv).startsWith("metric,value").contains("readings.captureRatePct,").contains("calibration.compliancePct,100.0");
        var http = Current.as(tadmin, () -> commercialApi.monthlyCsv(month));
        assertThat(http.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThatThrownBy(() -> reports.generate(T, "2026-13", "test")).isInstanceOf(ApiException.class).hasMessageContaining("YYYY-MM");
        assertThatThrownBy(() -> reports.generate(T, YearMonth.now(ZoneOffset.UTC).plusMonths(2).toString(), "test")).isInstanceOf(ApiException.class).hasMessageContaining("future");
        assertThatThrownBy(() -> Current.as(nurse, () -> commercialApi.monthly(month))).isInstanceOf(ApiException.class);
        assertThat(MonthlyReportService.csv(Json.obj().put("x", "=cmd|' /C calc'!A0"))).contains("'=cmd");
    }

    // ---------------------------------------------------------------- IM-E09-BL-005
    @Test
    @Order(4)
    void tenantHealthScoreIsBoundedAndExplained() {
        ObjectNode s = Current.as(tadmin, () -> commercialApi.health());
        assertThat(s.get("score").asInt()).isBetween(0, 100);
        assertThat(s.get("factors")).hasSize(5);
        assertThat(s.get("factors").get(0).get("explanation").asString()).contains("published");
        ObjectNode worst = TenantHealthService.score(new TenantHealthService.Inputs(0, 0, 0, 10_000, 0, 50, 0.0));
        ObjectNode best = TenantHealthService.score(new TenantHealthService.Inputs(40, 500, 100, 0, 10_000, 0, 1.0));
        ObjectNode odd = TenantHealthService.score(new TenantHealthService.Inputs(-3, -1, 0, -5, -7, -2, 7.0));
        assertThat(worst.get("score").asInt()).isEqualTo(0);
        assertThat(worst.get("grade").asString()).isEqualTo("CRITICAL");
        assertThat(best.get("score").asInt()).isEqualTo(100);
        assertThat(best.get("grade").asString()).isEqualTo("HEALTHY");
        assertThat(odd.get("score").asInt()).isBetween(0, 100);
        assertThatThrownBy(() -> Current.as(nurse, () -> commercialApi.health())).isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- IM-E09-BL-002
    @Test
    @Order(5)
    void billingExportIsHmacSignedAndReconciliationReportsDifferences() {
        meter(M + "-01", "observations.filed", 10);
        meter(M + "-02", "observations.filed", 5);
        meter(M + "-01", "harness.runs", 2);
        assertThatThrownBy(() -> Current.as(admin, () -> commercialApi.export(M, "json"))).isInstanceOf(ApiException.class);
        ObjectNode e = (ObjectNode) Current.as(tadmin, () -> commercialApi.export(M, "json")).getBody();
        assertThat(e.get("rows").asInt()).isEqualTo(3);
        assertThat(e.at("/totals/observations.filed").asLong()).isEqualTo(15);
        String csv = e.get("csv").asString(), jsonl = e.get("jsonl").asString();
        assertThat(csv).isEqualTo("tenant,day,meter,qty\n" + T + "," + M + "-01,harness.runs,2\n" + T + "," + M + "-01,observations.filed,10\n" + T + "," + M + "-02,observations.filed,5\n");
        assertThat(jsonl.lines()).hasSize(3).allSatisfy(l -> assertThat(Json.parse(l).get("tenant").asString()).isEqualTo(T));
        // independent verification with the key held in the secret store
        assertThat(e.get("keyId").asString()).isEqualTo("secret://" + BillingService.SECRET_NAME);
        byte[] key = Base64.getDecoder().decode(secrets.get(T, BillingService.SECRET_NAME).orElseThrow());
        assertThat(Hashing.hmacSha256(key, csv)).isEqualTo(e.at("/signatures/csv").asString());
        assertThat(Hashing.hmacSha256(key, jsonl)).isEqualTo(e.at("/signatures/jsonl").asString());
        assertThat(billing.verify(T, csv, e.at("/signatures/csv").asString(), "test")).isTrue();
        assertThat(billing.verify(T, csv.replace(",10\n", ",11\n"), e.at("/signatures/csv").asString(), "test")).isFalse();
        var raw = Current.as(tadmin, () -> commercialApi.export(M, "csv"));
        assertThat(raw.getHeaders().getFirst("X-IntiMed-Signature")).isEqualTo("hmac-sha256=" + e.at("/signatures/csv").asString());

        ObjectNode inv = Json.obj().put("month", M);
        inv.putArray("lines").add(Json.obj().put("meter", "observations.filed").put("qty", 15))
                .add(Json.obj().put("meter", "harness.runs").put("day", M + "-01").put("qty", 3))
                .add(Json.obj().put("meter", "devices.extra").put("qty", 1));
        ObjectNode rec = Current.as(tadmin, () -> commercialApi.reconcile(inv));
        assertThat(rec.get("status").asString()).isEqualTo("DIFFERENCES");
        assertThat(rec.get("matched").asInt()).isEqualTo(1);
        assertThat(rec.get("differenceCount").asInt()).isEqualTo(2);
        assertThat(rec.get("differences")).anySatisfy(d -> {
            assertThat(d.get("meter").asString()).isEqualTo("harness.runs");
            assertThat(d.get("delta").asLong()).isEqualTo(1);
            assertThat(d.get("kind").asString()).isEqualTo("QUANTITY_MISMATCH");
        }).anySatisfy(d -> assertThat(d.get("kind").asString()).isEqualTo("INVOICED_NOT_METERED"));
        ObjectNode exact = Json.obj().put("month", M);
        exact.putArray("lines").add(Json.obj().put("meter", "observations.filed").put("qty", 15)).add(Json.obj().put("meter", "harness.runs").put("qty", 2));
        assertThat(billing.reconcile(T, exact, "test").get("status").asString()).isEqualTo("RECONCILED");
        ObjectNode bad = Json.obj().put("month", M);
        bad.putArray("lines").add(Json.obj().put("meter", "DROP TABLE;").put("qty", 1));
        assertThatThrownBy(() -> billing.reconcile(T, bad, "test")).isInstanceOf(ApiException.class).hasMessageContaining("meter");
        assertThat(audit.byTarget(T, "billing/" + M)).extracting(a -> a.get("action")).contains("billing.export", "billing.reconcile");
    }

    private void meter(String day, String meter, long qty) {
        jdbc.sql("insert into metering(tenant_id, usage_day, meter, qty) values (:t,:d,:m,:q)").param("t", T).param("d", day).param("m", meter).param("q", qty).update();
    }

    // ---------------------------------------------------------------- IM-E09-BL-001
    @Test
    @Order(6)
    void trialTenantIsSuspendedOnExpiryAndOffboardedAfterGrace() {
        TenantService.Tenant t = trials.create("oc-trial", "Trial clinic", "eu-west", 7, 3, "user:sre");
        assertThat(t.plan()).isEqualTo("TRIAL");
        assertThat(t.settings().path("simulatorOnly").asBoolean()).isTrue();
        assertThat(store.get("oc-trial", "subject", TrialService.SANDBOX_SUBJECT)).isPresent();
        // simulator-only is enforced: no real endpoint can be registered, non-simulator live traffic is rejected
        assertThatThrownBy(() -> Current.as(new io.intimed.security.auth.IntiPrincipal("u-trial", "trial.admin", "oc-trial", "Trial admin",
                java.util.EnumSet.of(io.intimed.security.auth.Role.DEVICE_ADMIN), java.util.Set.of(), false),
                () -> securityController.register(Json.obj().put("name", "Real monitor gateway")))).hasMessageContaining("simulator traffic only");
        String cfgOnTrial = "cfg-none";
        var rejected = pipeline.process(io.intimed.engine.pipeline.Envelope.live("oc-trial", "ep-real-device", cfgOnTrial, "P4", "MSH|x", "10.0.0.9"));
        assertThat(rejected.status).isEqualTo("REJECTED");
        assertThat(rejected.error).contains("simulator traffic only");
        assertThat(store.require("oc-trial", "trial-sandbox", "sandbox").get("simulators")).isNotEmpty();
        assertThatThrownBy(() -> trials.create("oc-trial2", "x", "eu-west", 365, null, "user:sre")).isInstanceOf(ApiException.class);
        Instant now = Instant.now();
        assertThat(trials.sweep(now)).noneMatch(a -> a.get("tenant").asString().equals("oc-trial"));
        assertThat(trials.sweep(now.plus(8, ChronoUnit.DAYS))).anySatisfy(a -> {
            assertThat(a.get("tenant").asString()).isEqualTo("oc-trial");
            assertThat(a.get("action").asString()).isEqualTo("SUSPENDED");
        });
        assertThat(tenants.find("oc-trial").status()).isEqualTo("SUSPENDED");
        assertThatThrownBy(() -> tenants.require("oc-trial")).isInstanceOf(ApiException.class).hasMessageContaining("not active");
        assertThat(trials.sweep(now.plus(9, ChronoUnit.DAYS))).noneMatch(a -> a.get("tenant").asString().equals("oc-trial"));
        assertThat(trials.sweep(now.plus(11, ChronoUnit.DAYS))).anySatisfy(a -> assertThat(a.get("action").asString()).isEqualTo("OFFBOARDED"));
        assertThat(tenants.find("oc-trial").status()).isEqualTo("OFFBOARDED");
        assertThat(store.list("oc-trial", "subject")).isEmpty();
        assertThat(audit.byTarget("oc-trial", "tenant/oc-trial")).extracting(a -> a.get("action")).contains("trial.create", "trial.suspend", "tenant.offboard");
        assertThat(audit.verify().get("valid")).isEqualTo(true);
        assertThatThrownBy(() -> Current.as(tadmin, () -> commercialApi.trialList())).isInstanceOf(ApiException.class);
    }

    // ---------------------------------------------------------------- IM-E09-BL-006
    @Test
    @Order(7)
    void brandingValidatesColoursAndLogos() {
        byte[] png = new byte[64];
        System.arraycopy(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}, 0, png, 0, 8);
        ObjectNode ok = Json.obj().put("productName", "Acme Vitals").put("primaryColor", "#112233").put("accentColor", "#AABBCC")
                .put("supportEmail", "support@acme.example").put("supportUrl", "https://acme.example/help").put("logo", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        ObjectNode saved = Current.as(tadmin, () -> commercialApi.putBranding(ok));
        assertThat(saved.get("primaryColor").asString()).isEqualTo("#112233");
        assertThat(saved.get("accentColor").asString()).isEqualTo("#aabbcc");
        assertThat(saved.get("custom").asBoolean()).isTrue();
        assertThat(Current.as(nurse, () -> commercialApi.getBranding()).get("productName").asString()).isEqualTo("Acme Vitals");
        assertThat(branding.css(T)).contains("--im-primary:#112233").contains("--brand-2:#aabbcc");
        assertThatThrownBy(() -> Current.as(admin, () -> commercialApi.putBranding(Json.obj().put("productName", "x")))).isInstanceOf(ApiException.class);

        String svgOk = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"10\" height=\"10\" fill=\"#123456\"/></svg>";
        assertThat(Current.as(tadmin, () -> commercialApi.putBranding(Json.obj().put("logo", svg(svgOk)))).get("logo").asString()).startsWith("data:image/svg+xml;base64,");
        for (String evil : List.of(
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><rect/></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:x=\"http://www.w3.org/1999/xlink\"><a x:href=\"jav&#x61;script:alert(1)\"><rect/></a></svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x</div></foreignObject></svg>",
                "<!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><svg xmlns=\"http://www.w3.org/2000/svg\">&x;</svg>",
                "<svg xmlns=\"http://www.w3.org/2000/svg\"><image href=\"https://evil.example/x.png\"/></svg>",
                "<html><body>not svg</body></html>")) {
            assertThatThrownBy(() -> BrandingService.validateLogo(svg(evil))).as(evil).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> Current.as(tadmin, () -> commercialApi.putBranding(Json.obj().put("logo", svg("<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>")))))
                .isInstanceOf(ApiException.class).hasMessageContaining("script");
        assertThatThrownBy(() -> BrandingService.validateLogo("data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[70_000]))).hasMessageContaining("64 KiB");
        assertThatThrownBy(() -> BrandingService.validateLogo("data:image/gif;base64,R0lGODlh")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> BrandingService.validateLogo("data:image/png;base64," + Base64.getEncoder().encodeToString("not a png".getBytes(StandardCharsets.UTF_8)))).hasMessageContaining("PNG");
        assertThatThrownBy(() -> Current.as(tadmin, () -> commercialApi.putBranding(Json.obj().put("primaryColor", "red;}body{x:y")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Current.as(tadmin, () -> commercialApi.putBranding(Json.obj().put("supportUrl", "javascript:alert(1)")))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Current.as(tadmin, () -> commercialApi.putBranding(Json.obj().put("style", "x")))).isInstanceOf(ApiException.class);
        assertThat(audit.byTarget(T, "tenant/" + T)).anySatisfy(a -> assertThat(a.get("action")).isEqualTo("tenant.branding"));
    }

    private static String svg(String s) { return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    // ---------------------------------------------------------------- IM-E02-BL-007 / IM-E09-BL-004
    @Test
    @Order(8)
    void benchmarksAreKAnonymousAndCarryNoTenantIds() {
        for (int i = 1; i <= 5; i++) {
            String id = "oc-bench-" + i;
            tenants.create(id, "Bench org " + i, "eu-west", "ENTERPRISE", null, "test");
            ObjectNode c = Json.obj().put("id", "cfg-bench0").put("name", "Spiro " + i).put("modelId", "IMD-001").put("family", "spirometer").put("option", "P2")
                    .put("status", "PUBLISHED").put("certificationId", "cert-" + i);
            c.putArray("bindings").addObject().put("templateId", "TPL-18");
            store.put(id, "config", "cfg-bench0", c, "PUBLISHED", "IMD-001");
            store.put(id, "run", "run-bench" + i, Json.obj().put("id", "run-bench" + i).put("configId", "cfg-bench0").put("latencyP95Ms", 100 * i), "PASSED", "cfg-bench0");
            if (i <= 3) tenants.updateSettings(id, Json.obj().put("benchmarkOptIn", true), "test");
        }
        IntiPrincipal benchAdmin = users.create("oc-bench-1", "oc.bench.admin", PW, "Bench admin", EnumSet.of(Role.TENANT_ADMIN), "test").principal();
        // 3 contributing organisations < k=5 → the spirometer cell is suppressed
        ObjectNode b = Current.as(benchAdmin, () -> commercialApi.benchmarks());
        assertThat(b.get("optedIn").asBoolean()).isTrue();
        ObjectNode fam = bench(b, "family", "spirometer");
        assertThat(fam.get("peers").asString()).isEqualTo("SUPPRESSED");
        assertThat(fam.at("/own/configs").asInt()).isEqualTo(1);
        assertThat(benchmarks.contributeToKnowledgeBase()).isZero();
        // opt in two more → 5 organisations → released, de-identified
        tenants.updateSettings("oc-bench-4", Json.obj().put("benchmarkOptIn", true), "test");
        tenants.updateSettings("oc-bench-5", Json.obj().put("benchmarkOptIn", true), "test");
        ObjectNode b2 = Current.as(benchAdmin, () -> commercialApi.benchmarks());
        ObjectNode peers = (ObjectNode) bench(b2, "family", "spirometer").get("peers");
        assertThat(peers.get("organisations").asString()).isEqualTo("5-9");
        assertThat(peers.get("certifiedRatePct").asDouble()).isEqualTo(100.0);
        assertThat(peers.get("meanHarnessP95Ms").asLong()).isEqualTo(300);
        assertThat(bench(b2, "option", "P2").get("peers").isObject()).isTrue();
        assertThat(bench(b2, "template", "TPL-18").get("peers").isObject()).isTrue();
        String dump = Json.write(b2);
        assertThat(dump).doesNotContain("oc-bench").doesNotContain("Bench org").doesNotContain(T).doesNotContain("cfg-bench0");
        // a non-contributing tenant sees only its own figures
        ObjectNode mine = Current.as(tadmin, () -> commercialApi.benchmarks());
        assertThat(mine.get("optedIn").asBoolean()).isFalse();
        assertThat(mine.get("rows")).allSatisfy(r -> assertThat(r.get("peers").asString()).isEqualTo("NOT_OPTED_IN"));
        // released cells feed the knowledge base without tenant ids
        assertThat(benchmarks.contributeToKnowledgeBase()).isEqualTo(3);
        String kbDump = Json.write(kb.benchmarkAggregates());
        assertThat(kbDump).contains("spirometer").doesNotContain("oc-bench").doesNotContain(T);
        assertThatThrownBy(() -> Current.as(admin, () -> commercialApi.optIn(Json.obj().put("optIn", true)))).isInstanceOf(ApiException.class);
        assertThat(Current.as(tadmin, () -> commercialApi.optIn(Json.obj().put("optIn", true)))).containsEntry("optIn", true);
    }

    private static ObjectNode bench(ObjectNode b, String dim, String key) {
        for (var r : b.get("rows")) if (dim.equals(r.get("dimension").asString()) && key.equals(r.get("key").asString())) return (ObjectNode) r;
        throw new AssertionError("no benchmark row " + dim + "/" + key + " in " + Json.pretty(b));
    }
}
