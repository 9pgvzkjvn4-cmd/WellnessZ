package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceDiagnostics;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSecretStore;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.e32.catalogue.CatalogueSeedLoader;
import com.wellnessz.e32.support.CanonicalJson;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E32 – device catalogue, administration & provisioning console (contract §13, §20): seed totals
 * (133 models, option counts), option packs, the eight-step wizard end to end with the simulator test, nurse review,
 * four-eyes refusal, certification PDF, publish → provisioning → {@code DeviceCatalogueLookup}, secrets as references,
 * one-time enrolment token, clone / revise, governance export / import with diff, calibration / QC lockout and release,
 * quarantine on endpoint isolation, driver registry signatures and four-eyes, decommissioning with revocation, posture
 * exceptions, request-a-model and the negative role paths. E31 (diagnostics) and E34 (provisioning, secrets, signatures)
 * are replaced by mocks so the console is tested on its own contract.
 */
class Increment6ConsoleIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    DeviceSpis spis;
    @Autowired
    CatalogueSeedLoader seedLoader;

    @MockitoBean
    DeviceDiagnostics diagnostics;
    @MockitoBean
    DeviceEndpointProvisioning provisioning;
    @MockitoBean
    DeviceSecretStore secrets;
    @MockitoBean
    DevicePackageSignatures signatures;

    static final JwtRequestPostProcessor ADMIN = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
    static final JwtRequestPostProcessor APPROVER = user("deviceapprover1", "S8902", "/AIRLINE", "DEVICE_APPROVER");
    static final JwtRequestPostProcessor TESTER = user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER");
    static final JwtRequestPostProcessor SECOPS = user("secops1", "S8904", "/AIRLINE", "SECURITY_OPS");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor BIOMED = user("biomed1", "B1", null, "BIOMEDICAL");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor EMPLOYEE = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");

    @BeforeEach
    void mocks() {
        when(provisioning.state(anyString())).thenAnswer(i -> new DeviceEndpointProvisioning.EndpointState(i.getArgument(0),
                DeviceCodes.EndpointStatus.ACTIVE, Instant.now(), false));
        when(provisioning.provision(anyString(), any())).thenAnswer(i -> {
            DeviceEndpointProvisioning.ProvisionRequest r = i.getArgument(1);
            return r.existingEndpointId() != null
                    ? new DeviceEndpointProvisioning.EndpointIssue(r.existingEndpointId(), DeviceCodes.EndpointStatus.ACTIVE, null, null)
                    : new DeviceEndpointProvisioning.EndpointIssue("EP-" + r.deviceInstanceId(), DeviceCodes.EndpointStatus.PENDING_ENROLMENT,
                            "enrol-once-7Hq2", Instant.now().plusSeconds(86400));
        });
        when(diagnostics.runTest(anyString(), any())).thenAnswer(i -> new DeviceDiagnostics.TestRun(UUID.randomUUID(), "PASSED",
                List.of(new DeviceDiagnostics.TestStep("CONNECTION", "PASS", "mTLS authenticated, plaintext refused"),
                        new DeviceDiagnostics.TestStep("END_TO_END", "PASS", "sample result in the test form"),
                        new DeviceDiagnostics.TestStep("NEGATIVE_VECTORS", "PASS", "malformed and implausible refused")),
                "ab".repeat(32), Instant.now()));
        when(diagnostics.health(anyString())).thenAnswer(i -> new DeviceDiagnostics.ConnectorHealth(i.getArgument(0), "HEALTHY", Instant.now(), 12, 0,
                900L, 0, 0));
        when(diagnostics.trace(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> List.of(new DeviceDiagnostics.TraceEntry(UUID.randomUUID(),
                Instant.now(), "PARSE", "OK", null, "PID|1||E1001^^^WZ||Testperson^Erin||19850214|F\rOBX|1|NM|150021^MDC||124|mm[Hg]", "systolic→8480-6",
                1200L)));
        when(secrets.store(anyString(), anyString(), anyString(), any())).thenAnswer(i -> "devsec:" + UUID.randomUUID());
        when(signatures.verify(any(), anyString(), eq("pkg-test-2026"))).thenReturn(new DevicePackageSignatures.Verification(true, "pkg-test-2026",
                "Ed25519", null));
        when(signatures.verify(any(), anyString(), eq("pkg-unknown"))).thenReturn(new DevicePackageSignatures.Verification(false, "pkg-unknown",
                "Ed25519", "unknown_key"));
    }

    // ------------------------------------------------------------------ helpers

    MvcResult call(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, Object body) throws Exception {
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req.with(who)).andReturn();
    }

    JsonNode ok(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, Object body) throws Exception {
        MvcResult r = call(req, who, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 201);
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode problem(AbstractMockHttpServletRequestBuilder<?> req, JwtRequestPostProcessor who, Object body, int expected) throws Exception {
        MvcResult r = call(req, who, body);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? json.readTree("{}") : json.readTree(s);
    }

    static Set<String> errorCodes(JsonNode problem) {
        Set<String> out = new HashSet<>();
        problem.path("errors").forEach(e -> out.add(e.path("code").asText()));
        return out;
    }

    static String stepStatus(JsonNode cfg, int n) {
        return cfg.path("steps").get(n - 1).path("status").asText();
    }

    DeviceCatalogueLookup lookup() {
        return spis.catalogue();
    }

    // ------------------------------------------------------------------ catalogue seed (E32-F01)

    @Test
    void catalogueIsSeededWith133ModelsElevenFamiliesAndTheContractOptionTotals() throws Exception {
        JsonNode families = ok(get("/api/v1/device-catalogue/families"), ADMIN, null);
        assertThat(families).hasSize(11);
        int total = 0;
        for (JsonNode f : families) {
            total += f.path("models").asInt();
        }
        assertThat(total).isEqualTo(133);
        Map<String, Integer> expected = Map.of("P1", 73, "P2", 66, "P4", 40, "P6", 32, "P3", 22, "P10", 13, "P9", 12, "P5", 12, "P7", 7, "P8", 4);
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertThat(ok(get("/api/v1/device-catalogue/models?option=" + e.getKey()), BIOMED, null)).as(e.getKey()).hasSize(e.getValue());
        }
        JsonNode md1 = ok(get("/api/v1/device-catalogue/models/MD-001"), NURSE, null);
        assertThat(md1.path("manufacturer").asText()).isEqualTo("ndd Medical Technologies");
        List<String> supported = new java.util.ArrayList<>();
        md1.path("options").forEach(o -> {
            if (o.path("supported").asBoolean()) {
                supported.add(o.path("option").asText());
            }
        });
        assertThat(supported).containsExactly("P1", "P2", "P3", "P4");
        assertThat(md1.path("regulatory").get(0).path("status").asText()).isEqualTo("CLEARED");
        assertThat(md1.path("regulatory").get(0).path("productCodes").get(0).asText()).isEqualTo("BZG");
        assertThat(md1.path("confidence").asText()).isEqualTo("High");
        assertThat(md1.path("drivers")).isNotEmpty();
        assertThat(ok(get("/api/v1/device-catalogue/models?q=lifeloc"), ADMIN, null)).hasSize(1);
        assertThat(ok(get("/api/v1/device-catalogue/compare?models=MD-001,MD-003"), ADMIN, null)).hasSize(2);
        JsonNode options = ok(get("/api/v1/device-catalogue/options"), ADMIN, null);
        assertThat(options).hasSize(10);
        assertThat(options.get(0).path("help").path("ar").asText()).isNotBlank();
        assertThat(ok(get("/api/v1/device-catalogue/options/P4"), ADMIN, null).path("parameterSchema").path("properties").has("mllp")).isTrue();
        problem(get("/api/v1/device-catalogue/families"), EMPLOYEE, null, 403);

        // idempotent seed: same file → nothing to do; a new file version keeps administrator edits
        assertThat(seedLoader.load()).isNull();
        ok(put("/api/v1/device-catalogue/models/MD-133"), ADMIN, Map.of("regulatory", List.of(Map.of("market", "UK", "status", "NOT_REQUIRED",
                "regime", "UKCA / MHRA"))));
        jdbc.update("update devicecat.seed_state set sha256 = 'previous-version' where seed_name = 'device-inventory'");
        CatalogueSeedLoader.Result again = seedLoader.load();
        assertThat(again).isNotNull();
        assertThat(again.inserted()).isZero();
        assertThat(again.updated()).isEqualTo(133);
        JsonNode md133 = ok(get("/api/v1/device-catalogue/models/MD-133"), ADMIN, null);
        assertThat(md133.path("locallyModified").asBoolean()).isTrue();
        assertThat(md133.path("regulatory").get(0).path("market").asText()).isEqualTo("UK");
        assertThat(jdbc.queryForObject("select count(*) from devicecat.model where inventory_id is not null", Integer.class)).isEqualTo(133);
    }

    // ------------------------------------------------------------------ the eight-step wizard (E32-F02)

    @Test
    void fullWizardWithFourEyesRefusalCertificationPublishAndLookup() throws Exception {
        JsonNode cfg = ok(post("/api/v1/device-configurations"), ADMIN, Map.of("modelCode", "MD-027", "siteCode", "LHR"));
        String id = cfg.path("configurationId").asText();
        String dev = cfg.path("deviceInstanceId").asText();
        assertThat(cfg.path("status").asText()).isEqualTo("DRAFT");
        assertThat(stepStatus(cfg, 1)).isEqualTo("VALID");

        // step 2: unsupported options hidden; an option without a driver package is refused
        JsonNode opts = ok(get("/api/v1/device-configurations/" + id + "/options"), ADMIN, null);
        List<String> offered = new java.util.ArrayList<>();
        opts.forEach(o -> offered.add(o.path("option").asText()));
        assertThat(offered).containsExactly("P1", "P2", "P4");
        assertThat(errorCodes(problem(put("/api/v1/device-configurations/" + id + "/steps/2"), ADMIN, Map.of("option", "P9"), 422)))
                .contains("option_not_supported");
        assertThat(errorCodes(problem(put("/api/v1/device-configurations/" + id + "/steps/2"), ADMIN, Map.of("option", "P1"), 422)))
                .contains("no_driver_for_option");
        problem(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of(), 422);
        cfg = ok(put("/api/v1/device-configurations/" + id + "/steps/2"), ADMIN, Map.of("option", "P4"));
        assertThat(stepStatus(cfg, 2)).isEqualTo("VALID");
        assertThat(cfg.path("driverPackageId").asText()).isEqualTo("vitals.welchallyn.csm-pcd01");

        // step 3: checklist with auto-checks (endpoint enrolment via E34, posture, calibration evidence)
        cfg = ok(put("/api/v1/device-configurations/" + id + "/steps/3"), ADMIN, Map.of("items", List.of(
                Map.of("code", "NETWORK_ZONE", "status", "DONE", "evidence", Map.of("reference", "DEVICE_VLAN", "text", "FW change CHG-2026-0412")))));
        assertThat(stepStatus(cfg, 3)).isEqualTo("IN_PROGRESS");
        cfg = ok(put("/api/v1/device-configurations/" + id + "/steps/3"), ADMIN, Map.of("items", List.of(
                Map.of("code", "ENDPOINT_IDENTITY", "evidence", Map.of("reference", "EP-LHR-GW-01")),
                Map.of("code", "CERTIFICATES", "status", "DONE", "evidence", Map.of("reference", "PKI-REQ-7781")),
                Map.of("code", "CALIBRATION_EVIDENCE", "evidence", Map.of("documentId", "CAL-CERT-2026-118", "text", "Vendor calibration certificate")))));
        assertThat(stepStatus(cfg, 3)).as(cfg.toString()).isEqualTo("VALID");
        ok(post("/api/v1/device-configurations/" + id + "/steps/3/auto-check"), ADMIN, null);

        // step 4: schema validation error, then valid with defaults from the driver package
        JsonNode defaults = ok(get("/api/v1/device-configurations/" + id + "/steps/4/defaults"), ADMIN, null);
        assertThat(defaults.path("parameters").path("mllp").path("port").asInt()).isEqualTo(2575);
        assertThat(defaults.path("parameters").path("messageProfile").asText()).isEqualTo("IHE_PCD_01");
        Map<String, Object> params = Map.of("deviceAddress", Map.of("ip", "10.20.30.77"),
                "mllp", Map.of("listenerEndpointId", "EP-LHR-GW-01", "port", 80),
                "msh", Map.of("sendingApplication", "CONNEX", "sendingFacility", "LHR_OH", "receivingFacility", "WZ"));
        JsonNode invalid = problem(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", params), 422);
        assertThat(invalid.path("code").asText()).isEqualTo("step_invalid");
        assertThat(invalid.path("errors").toString()).contains("parameters.mllp.port").contains("minimum");
        params = Map.of("deviceAddress", Map.of("ip", "10.20.30.77"), "mllp", Map.of("listenerEndpointId", "EP-LHR-GW-01"),
                "msh", Map.of("sendingApplication", "CONNEX", "sendingFacility", "LHR_OH", "receivingFacility", "WZ"));
        problem(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", params, "common", Map.of("verifyBeforePost", false)), 422);
        cfg = ok(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", params,
                "common", Map.of("qualityGateAdditions", List.of("CLOCK_SKEW"))));
        assertThat(stepStatus(cfg, 4)).isEqualTo("VALID");
        assertThat(cfg.path("steps").get(3).path("data").path("parameters").path("mllp").path("tls").asText()).isEqualTo("REQUIRED");

        // step 5: association and bindings with a golden-dataset preview
        assertThat(errorCodes(problem(put("/api/v1/device-configurations/" + id + "/steps/5"), ADMIN, Map.of("associationMethods", List.of("SESSION"),
                "siteCode", "LHR", "stationCode", "NOWHERE-1"), 422))).contains("unknown_station");
        cfg = ok(put("/api/v1/device-configurations/" + id + "/steps/5"), ADMIN, Map.of("associationMethods", List.of("WORKLIST", "SESSION"),
                "siteCode", "LHR", "stationCode", "LHR-OH-ROOM-1", "services", List.of("PERIODIC_MEDICAL"), "formCodes", List.of("E03.DEVICE_RESULT.VITALS")));
        assertThat(stepStatus(cfg, 5)).isEqualTo("VALID");
        JsonNode preview = ok(post("/api/v1/device-configurations/" + id + "/steps/5/preview"), ADMIN, Map.of("datasetId", "normal"));
        JsonNode sys = null;
        for (JsonNode f : preview.path("forms").get(0).path("fields")) {
            if ("systolic".equals(f.path("key").asText())) {
                sys = f;
            }
        }
        assertThat(sys).isNotNull();
        assertThat(sys.path("value").asInt()).isEqualTo(124);
        assertThat(sys.path("formField").asText()).isEqualTo("measurements.systolic");
        assertThat(sys.path("unit").asText()).isEqualTo("mm[Hg]");

        // step 6: simulator + live tests through the diagnostics SPI, nurse review of the test form
        problem(post("/api/v1/device-configurations/" + id + "/submit"), ADMIN, null, 422);
        JsonNode run = ok(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "SIMULATOR", "datasetId", "normal"));
        assertThat(run.path("status").asText()).isEqualTo("PASSED");
        assertThat(run.path("evidenceSha256").asText()).hasSize(64);
        cfg = ok(get("/api/v1/device-configurations/" + id), ADMIN, null);
        assertThat(cfg.path("steps").get(5).path("errors").toString()).contains("live_test_required", "nurse_review_required");
        ok(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "LIVE"));
        problem(post("/api/v1/device-configurations/" + id + "/steps/6/nurse-review"), ADMIN, Map.of("outcome", "ACCEPTED"), 403);
        cfg = ok(post("/api/v1/device-configurations/" + id + "/steps/6/nurse-review"), NURSE, Map.of("outcome", "ACCEPTED", "note", "Values match display"));
        assertThat(stepStatus(cfg, 6)).isEqualTo("VALID");

        // step 7: submit, four-eyes refusal, approval with certification PDF
        cfg = ok(post("/api/v1/device-configurations/" + id + "/submit"), ADMIN, null);
        assertThat(cfg.path("status").asText()).isEqualTo("AWAITING_APPROVAL");
        problem(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", params), 409);
        JsonNode fourEyes = problem(post("/api/v1/device-configurations/" + id + "/approve"), ADMIN, Map.of("note", "self"), 409);
        assertThat(fourEyes.path("code").asText()).isEqualTo("four_eyes_violation");
        problem(post("/api/v1/device-configurations/" + id + "/approve"), EMPLOYEE, null, 403);
        problem(post("/api/v1/device-configurations/" + id + "/approve"), SECOPS, null, 403);
        JsonNode queue = ok(get("/api/v1/device-governance/approvals"), APPROVER, null);
        assertThat(queue.toString()).contains(id);
        assertThat(lookup().instance(dev)).isEmpty();
        cfg = ok(post("/api/v1/device-configurations/" + id + "/approve"), APPROVER, Map.of("note", "Evidence complete"));
        assertThat(cfg.path("status").asText()).isEqualTo("CERTIFIED");
        assertThat(cfg.path("approvedBy").asText()).isEqualTo("deviceapprover1");
        JsonNode cert = ok(get("/api/v1/device-configurations/" + id + "/certification"), user("auditor1", null, null, "AUDITOR"), null);
        assertThat(cert.path("certificationId").asText()).startsWith("DCR-");
        assertThat(cert.path("snapshotSha256").asText()).hasSize(64);
        assertThat(cert.path("submittedBy").asText()).isEqualTo("deviceadmin1");
        assertThat(cert.path("tests")).hasSize(2);
        assertThat(cert.path("dataFlow").path("bindings").get(0).path("target").asText()).isEqualTo("E03.RESULT");
        MvcResult pdf = call(get("/api/v1/device-configurations/" + id + "/certificate.pdf"), ADMIN, null);
        assertThat(pdf.getResponse().getStatus()).isEqualTo(200);
        assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // step 8: publish → provisioning (existing gateway, no token) → visible through DeviceCatalogueLookup
        JsonNode pub = ok(post("/api/v1/device-configurations/" + id + "/publish"), ADMIN, null);
        assertThat(pub.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(pub.path("endpointId").asText()).isEqualTo("EP-LHR-GW-01");
        assertThat(pub.has("enrolmentToken")).isFalse();
        DeviceCatalogueLookup.DeviceInstance inst = lookup().instance(dev).orElseThrow();
        assertThat(inst.published()).isTrue();
        assertThat(inst.option()).isEqualTo(DeviceCodes.Option.P4_HL7_MLLP);
        assertThat(inst.family()).isEqualTo(DeviceCodes.Family.VITALS);
        assertThat(inst.categories()).contains(DeviceCodes.Category.VITALS);
        assertThat(inst.associationMethods()).containsExactly("WORKLIST", "SESSION");
        assertThat(inst.configVersion()).isEqualTo(1);
        assertThat(lookup().instancesForEndpoint("EP-LHR-GW-01", null)).extracting(DeviceCatalogueLookup.DeviceInstance::deviceInstanceId).contains(dev);
        assertThat(lookup().instancesAtStation("LHR", "LHR-OH-ROOM-1", DeviceCodes.Category.VITALS))
                .extracting(DeviceCatalogueLookup.DeviceInstance::deviceInstanceId).contains(dev, "DEV-000003");
        DeviceCatalogueLookup.FormBinding binding = lookup().binding(dev, "E03.DEVICE_RESULT.VITALS").orElseThrow();
        assertThat(binding.target()).isEqualTo("E03.RESULT");
        assertThat(binding.verifyBeforePost()).isTrue();
        assertThat(binding.qualityGates()).containsSubsequence("CALIBRATION", "CLOCK_SKEW", "VERIFY_BEFORE_POST");
        assertThat(binding.fields()).containsEntry("systolic", "measurements.systolic");
        assertThat(lookup().driverPackage("vitals.welchallyn.csm-pcd01", "1.0.0")).isPresent();
        assertThat(lookup().qcStatus(dev, LocalDate.now(), null).verdict()).isEqualTo("DUE");
        ok(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", dev, "checkType", "BP_VALIDATION_INTERVAL", "outcome", "PASS"));
        assertThat(lookup().qcStatus(dev, LocalDate.now(), null).verdict()).isEqualTo("VALID");

        // change history, data flow, fleet
        JsonNode history = ok(get("/api/v1/device-configurations/" + id + "/history"), ADMIN, null);
        List<String> actions = new java.util.ArrayList<>();
        history.forEach(h -> actions.add(h.path("action").asText()));
        assertThat(actions).contains("CREATE", "STEP", "TEST", "NURSE_REVIEW", "SUBMIT", "APPROVE", "PUBLISH");
        assertThat(ok(get("/api/v1/device-configurations/" + id + "/data-flow"), ADMIN, null).path("gates").toString()).contains("CLOCK_SKEW");
        JsonNode fleet = ok(get("/api/v1/device-fleet?siteCode=LHR"), ADMIN, null);
        assertThat(fleet.toString()).contains(dev).contains("HEALTHY");

        // clone to another station (new DRAFT at step 5) and revise (published version keeps running)
        JsonNode clone = ok(post("/api/v1/device-configurations/" + id + "/clone"), ADMIN, Map.of("siteCode", "LHR", "stationCode", "LHR-OH-ROOM-2"));
        assertThat(clone.path("currentStep").asInt()).isEqualTo(5);
        assertThat(clone.path("clonedFrom").asText()).isEqualTo(id);
        assertThat(clone.path("deviceInstanceId").asText()).isNotEqualTo(dev);
        JsonNode rev = ok(post("/api/v1/device-configurations/" + id + "/revise"), ADMIN, null);
        assertThat(rev.path("version").asInt()).isEqualTo(2);
        assertThat(rev.path("status").asText()).isEqualTo("DRAFT");
        problem(post("/api/v1/device-configurations/" + id + "/revise"), ADMIN, null, 409);
        assertThat(lookup().instance(dev).orElseThrow().configVersion()).isEqualTo(1);
    }

    /** The demo draft paused at step 4 (fit tester, vendor cloud): resume, secrets via the vault, one-time enrolment token. */
    @Test
    void pausedDraftResumesSecretsAreReferencesAndTheEnrolmentTokenIsShownOnce() throws Exception {
        JsonNode cfg = ok(get("/api/v1/device-configurations/CFG-000009"), ADMIN, null);
        assertThat(cfg.path("paused").asBoolean()).isTrue();
        assertThat(cfg.path("currentStep").asInt()).isEqualTo(4);
        Map<String, Object> params = Map.of("webhook", Map.of("enabled", true), "poll", Map.of("enabled", false), "residencyRegion", "UK");
        problem(put("/api/v1/device-configurations/CFG-000009/steps/4"), ADMIN, Map.of("parameters", params), 409);
        ok(post("/api/v1/device-configurations/CFG-000009/resume"), ADMIN, null);
        assertThat(errorCodes(problem(put("/api/v1/device-configurations/CFG-000009/steps/4"), ADMIN, Map.of("parameters", params), 422)))
                .contains("required");
        assertThat(errorCodes(problem(put("/api/v1/device-configurations/CFG-000009/steps/4"), ADMIN,
                Map.of("parameters", Map.of("webhook", Map.of("enabled", true, "webhookSecretRef", "plain-secret"), "poll", Map.of("enabled", false),
                        "residencyRegion", "UK")), 422))).contains("secret_inline");
        cfg = ok(put("/api/v1/device-configurations/CFG-000009/steps/4"), ADMIN, Map.of("parameters", params,
                "secrets", Map.of("webhook.webhookSecretRef", "s3cr3t-hmac-value")));
        assertThat(stepStatus(cfg, 4)).isEqualTo("VALID");
        assertThat(cfg.path("steps").get(3).path("data").path("parameters").path("webhook").path("webhookSecretRef").path("set").asBoolean()).isTrue();
        assertThat(cfg.toString()).doesNotContain("s3cr3t-hmac-value").doesNotContain("devsec:");
        verify(secrets).store(eq("deviceadmin1"), eq("device-configuration:CFG-000009"), eq("webhook.webhookSecretRef"), any());
        ok(put("/api/v1/device-configurations/CFG-000009/steps/5"), ADMIN, Map.of("associationMethods", List.of("SESSION", "VENDOR_ID_MATCH"),
                "siteCode", "LHR", "stationCode", "LHR-OH-ROOM-2"));
        ok(post("/api/v1/device-configurations/CFG-000009/steps/6/tests"), ADMIN, Map.of("mode", "SIMULATOR"));
        ok(post("/api/v1/device-configurations/CFG-000009/steps/6/tests"), ADMIN, Map.of("mode", "LIVE"));
        ok(post("/api/v1/device-configurations/CFG-000009/steps/6/nurse-review"), user("nursinghead1", "NH1", null, "NURSING_HEAD"),
                Map.of("outcome", "ACCEPTED"));
        ok(post("/api/v1/device-configurations/CFG-000009/submit"), ADMIN, null);
        ok(post("/api/v1/device-configurations/CFG-000009/approve"), APPROVER, null);
        JsonNode pub = ok(post("/api/v1/device-configurations/CFG-000009/publish"), ADMIN, null);
        assertThat(pub.path("enrolmentToken").asText()).isEqualTo("enrol-once-7Hq2");
        assertThat(pub.path("endpointId").asText()).isEqualTo("EP-DEV-000009");
        String reloaded = ok(get("/api/v1/device-configurations/CFG-000009"), ADMIN, null).toString();
        assertThat(reloaded).doesNotContain("enrol-once-7Hq2");
        assertThat(jdbc.queryForObject("select count(*) from devicecat.configuration where data like '%enrol-once-7Hq2%'", Integer.class)).isZero();
        assertThat(lookup().instance("DEV-000009").orElseThrow().option()).isEqualTo(DeviceCodes.Option.P6_CLOUD_API);
    }

    // ------------------------------------------------------------------ governance: export / import with diff (E32-F06)

    @Test
    void exportImportWithDiffNeedsASecondPersonAndCreatesADraftAtStepSix() throws Exception {
        JsonNode export = ok(get("/api/v1/device-governance/export?configurationIds=CFG-000007"), ADMIN, null);
        assertThat(export.path("sha256").asText()).hasSize(64);
        assertThat(export.toString()).doesNotContain("devsec:");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = json.readValue(export.toString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) ((List<Object>) payload.get("items")).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> mllp = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) item.get("snapshot")).get("parameters")).get("mllp");
        mllp.put("port", 2599);
        problem(post("/api/v1/device-governance/imports"), ADMIN, payload, 422);
        payload.put("sha256", com.wellnessz.e32.support.Docs.sha256(CanonicalJson.write(payload.get("items"))));
        JsonNode staged = ok(post("/api/v1/device-governance/imports"), ADMIN, payload);
        assertThat(staged.path("items").get(0).path("action").asText()).isEqualTo("UPDATE");
        assertThat(staged.path("items").get(0).path("diff").toString()).contains("parameters.mllp.port").contains("2599");
        String importId = staged.path("importId").asText();
        assertThat(problem(post("/api/v1/device-governance/imports/" + importId + "/approve"), ADMIN, null, 409).path("code").asText())
                .isEqualTo("four_eyes_violation");
        JsonNode approved = ok(post("/api/v1/device-governance/imports/" + importId + "/approve"), APPROVER, null);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        JsonNode draft = ok(get("/api/v1/device-configurations/CFG-000007"), ADMIN, null);
        assertThat(draft.path("version").asInt()).isEqualTo(2);
        assertThat(draft.path("currentStep").asInt()).isEqualTo(6);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(lookup().instance("DEV-000007").orElseThrow().configVersion()).isEqualTo(1);
        JsonNode changes = ok(get("/api/v1/device-governance/changes?configurationId=CFG-000007"), user("auditor1", null, null, "AUDITOR"), null);
        assertThat(changes.toString()).contains("IMPORT");

        // templates and batch provisioning for a new clinic
        JsonNode template = ok(post("/api/v1/device-governance/templates"), ADMIN, Map.of("name", "LHR standard room", "scope", "SITE",
                "items", List.of(Map.of("modelCode", "MD-037", "option", "P3", "stationPattern", "LHR-OH-ROOM-*"))));
        JsonNode batch = ok(post("/api/v1/device-governance/templates/" + template.path("templateId").asText() + "/batch"), ADMIN,
                Map.of("siteCode", "LHR", "stations", List.of("LHR-OH-ROOM-1", "LHR-OH-ROOM-2", "LHR-OH-BOOTH-1")));
        assertThat(batch.path("created").asInt()).isEqualTo(2);
        assertThat(ok(get("/api/v1/device-governance/batches/" + batch.path("batchId").asText()), ADMIN, null).path("items")).hasSize(2);
    }

    // ------------------------------------------------------------------ decommissioning (E32-F11)

    @Test
    void decommissioningNeedsFourEyesRevokesTheEndpointAndRemovesTheInstance() throws Exception {
        assertThat(lookup().instance("DEV-000002")).isPresent();
        problem(post("/api/v1/device-configurations/CFG-000002/decommission"), ADMIN, Map.of("reason", "replaced", "disposal", "SOLD"), 400);
        ok(post("/api/v1/device-configurations/CFG-000002/decommission"), ADMIN, Map.of("reason", "Replaced by a new audiometer", "disposal",
                "RETURNED_TO_VENDOR"));
        assertThat(problem(post("/api/v1/device-configurations/CFG-000002/decommission/approve"), ADMIN, null, 409).path("code").asText())
                .isEqualTo("four_eyes_violation");
        JsonNode done = ok(post("/api/v1/device-configurations/CFG-000002/decommission/approve"), APPROVER, Map.of("note", "ok"));
        assertThat(done.path("status").asText()).isEqualTo("DECOMMISSIONED");
        assertThat(done.path("disposal").path("disposal").asText()).isEqualTo("RETURNED_TO_VENDOR");
        verify(provisioning).revoke("deviceapprover1", "EP-LHR-AGT-01", "DEV-000002", "DECOMMISSIONED");
        assertThat(lookup().instance("DEV-000002")).isEmpty();
        assertThat(lookup().instancesForEndpoint("EP-LHR-AGT-01", "AU300-1001")).isEmpty();
        problem(post("/api/v1/device-configurations/CFG-000002/clone"), ADMIN, Map.of("siteCode", "LHR", "stationCode", "LHR-OH-ROOM-2"), 409);
    }

    // ------------------------------------------------------------------ calibration, QC and lockout (E32-F04)

    @Test
    void expiredSyringeCheckLocksTheSpirometerUntilAPassingCheck() throws Exception {
        DeviceCatalogueLookup.QcStatus qc = lookup().qcStatus("DEV-000001", LocalDate.now(), null);
        assertThat(qc.verdict()).isEqualTo("DUE");
        assertThat(qc.checkType()).isEqualTo("SPIRO_SYRINGE_3L");
        assertThat(qc.blocks()).isTrue();
        JsonNode status = ok(get("/api/v1/device-calibration/status?siteCode=LHR&stationCode=LHR-OH-ROOM-2"), NURSE, null);
        assertThat(status.toString()).contains("DEV-000001");
        JsonNode spiro = null;
        for (JsonNode s : status) {
            if ("DEV-000001".equals(s.path("deviceInstanceId").asText())) {
                spiro = s;
            }
        }
        assertThat(spiro.path("lockedOut").asBoolean()).isTrue();
        assertThat(spiro.path("calibration").path("equipmentDeviceId").asText()).isEqualTo("SPIRO-HQ-01");
        assertThat(problem(post("/api/v1/device-calibration/instances/DEV-000001/release"), ADMIN, null, 409).path("code").asText()).isEqualTo("qc_due");
        problem(post("/api/v1/device-calibration/checks"), EMPLOYEE, Map.of("deviceInstanceId", "DEV-000001", "checkType", "SPIRO_SYRINGE_3L",
                "outcome", "PASS"), 403);
        problem(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", "DEV-000001", "checkType", "AUDIO_DAILY_BIOLOGICAL",
                "outcome", "PASS"), 422);
        JsonNode after = ok(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", "DEV-000001", "checkType", "SPIRO_SYRINGE_3L",
                "outcome", "PASS", "values", Map.of("volumeL", 3.02)));
        assertThat(after.path("lockedOut").asBoolean()).isFalse();
        assertThat(lookup().qcStatus("DEV-000001", LocalDate.now(), null).verdict()).isEqualTo("VALID");
        ok(post("/api/v1/device-calibration/instances/DEV-000001/release"), ADMIN, null);
        ok(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", "DEV-000001", "checkType", "SPIRO_LINEARITY", "outcome", "FAIL"));
        assertThat(lookup().qcStatus("DEV-000001", LocalDate.now(), null).verdict()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from devicecat.lockout_event where device_instance_id = 'DEV-000001' and event = 'LOCKED'",
                Integer.class)).isPositive();
        JsonNode history = ok(get("/api/v1/device-calibration/instances/DEV-000001/history"), BIOMED, null);
        assertThat(history.path("history").size()).isGreaterThanOrEqualTo(4);
        // QC lots for the point-of-care analyser
        assertThat(lookup().qcStatus("DEV-000006", LocalDate.now(), "AF-LOT-2026-10").verdict()).isEqualTo("VALID");
        assertThat(lookup().qcStatus("DEV-000006", LocalDate.now(), "UNKNOWN-LOT").verdict()).isEqualTo("DUE");
        // compliance report, relaxing a schedule needs approval, E14 migration report
        assertThat(ok(get("/api/v1/device-calibration/report?siteCode=LHR"), ADMIN, null).path("sites").get(0).path("devices").asInt()).isPositive();
        MvcResult csv = call(get("/api/v1/device-calibration/report?format=csv"), ADMIN, null);
        assertThat(csv.getResponse().getContentAsString()).startsWith("siteCode,deviceInstanceId");
        JsonNode relax = ok(put("/api/v1/device-calibration/schedules/AUDIOMETRY/AUDIO_DAILY_BIOLOGICAL"), ADMIN, Map.of("intervalHours", 48));
        assertThat(relax.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(problem(post("/api/v1/device-governance/approvals/" + relax.path("pendingApprovalId").asText() + "/approve"), ADMIN, null, 409)
                .path("code").asText()).isEqualTo("four_eyes_violation");
        JsonNode migration = ok(get("/api/v1/device-calibration/migration-report"), BIOMED, null);
        assertThat(migration.path("registerItems").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(migration.path("linked").asInt()).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ endpoint isolation → quarantine, fleet, diagnostics

    @Test
    void endpointIsolationQuarantinesInstancesAndReleaseDoesNotRestoreThem() throws Exception {
        for (DeviceEndpointStatusListener l : spis.endpointListeners()) {
            l.endpointStatusChanged("EP-LHR-TAB-01", Set.of(), DeviceCodes.EndpointStatus.ISOLATED, "MANUAL_ISOLATION");
        }
        assertThat(lookup().instance("DEV-000004").orElseThrow().status()).isEqualTo("QUARANTINED");
        for (DeviceEndpointStatusListener l : spis.endpointListeners()) {
            l.endpointStatusChanged("EP-LHR-TAB-01", Set.of(), DeviceCodes.EndpointStatus.ACTIVE, "RELEASED");
        }
        assertThat(lookup().instance("DEV-000004").orElseThrow().status()).isEqualTo("QUARANTINED");
        JsonNode sites = ok(get("/api/v1/device-fleet/sites"), SECOPS, null);
        assertThat(sites.toString()).contains("\"quarantined\"");
        JsonNode alerts = ok(get("/api/v1/device-fleet/alerts"), ADMIN, null);
        String alertId = null;
        for (JsonNode a : alerts) {
            if ("DEV-000004".equals(a.path("deviceInstanceId").asText()) && "QUARANTINED".equals(a.path("type").asText())) {
                alertId = a.path("alertId").asText();
            }
        }
        assertThat(alertId).isNotNull();
        ok(post("/api/v1/device-fleet/alerts/" + alertId + "/acknowledge"), ADMIN, null);
        JsonNode ticket = ok(post("/api/v1/device-fleet/alerts/" + alertId + "/ticket"), ADMIN, null);
        assertThat(ticket.path("ticketRef").asText()).startsWith("ITSM-");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where destination = 'itsm-tickets'", Integer.class)).isPositive();
        // masked diagnostics, no person data for console roles; managers see counts only
        JsonNode trace = ok(get("/api/v1/device-diagnostics/DEV-000003/trace"), ADMIN, null);
        assertThat(trace.toString()).doesNotContain("Testperson", "E1001", "19850214", "|124|").contains("«masked»");
        problem(get("/api/v1/device-diagnostics/DEV-000003/trace"), NURSE, null, 403);
        JsonNode ws = ok(get("/api/v1/workspace/device-fleet"), MANAGER, null);
        assertThat(ws.path("sites")).isNotEmpty();
        assertThat(ws.toString()).doesNotContain("DEV-0000");
        problem(get("/api/v1/device-fleet"), MANAGER, null, 403);
        JsonNode versions = ok(put("/api/v1/device-fleet/instances/DEV-000001/versions"), ADMIN, Map.of("softwareVersion", "2.1"));
        assertThat(versions.path("compatible").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ driver registry (signatures, four-eyes, revocation)

    @Test
    void driverRegistryVerifiesSignaturesAndCertificationNeedsASecondPerson() throws Exception {
        JsonNode existing = ok(get("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.0.0"), ADMIN, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = json.readValue(existing.path("manifest").toString(), Map.class);
        pkg.put("version", "1.1.0");
        pkg.put("signature", Map.of("keyId", "pkg-unknown", "algorithm", "Ed25519", "value", "c2lnbmF0dXJl"));
        assertThat(problem(post("/api/v1/device-catalogue/drivers"), TESTER, Map.of("packageJson", pkg, "conformanceRunId", "CR-1"), 422)
                .path("code").asText()).isEqualTo("signature_invalid");
        problem(post("/api/v1/device-catalogue/drivers"), ADMIN, Map.of("packageJson", pkg, "conformanceRunId", "CR-1"), 403);
        pkg.put("signature", Map.of("keyId", "pkg-test-2026", "algorithm", "Ed25519", "value", "c2lnbmF0dXJl"));
        JsonNode reg = ok(post("/api/v1/device-catalogue/drivers"), TESTER, Map.of("packageJson", pkg, "conformanceRunId", "CR-2",
                "conformanceEvidenceSha256", "cd".repeat(32)));
        assertThat(reg.path("status").asText()).isEqualTo("REGISTERED");
        problem(post("/api/v1/device-catalogue/drivers"), TESTER, Map.of("packageJson", pkg, "conformanceRunId", "CR-2"), 409);
        assertThat(problem(post("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.1.0/certify"),
                user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER", "DEVICE_APPROVER"), null, 409).path("code").asText())
                .isEqualTo("four_eyes_violation");
        assertThat(ok(post("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.1.0/certify"), APPROVER, null).path("status").asText())
                .isEqualTo("CERTIFIED");
        Map<String, Object> bad = new java.util.LinkedHashMap<>(pkg);
        bad.put("version", "1.2.0");
        bad.put("protocol", Map.of("mllpPort", 2575, "password", "letmein"));
        assertThat(problem(post("/api/v1/device-catalogue/drivers"), TESTER, Map.of("packageJson", bad, "conformanceRunId", "CR-3"), 422)
                .path("errors").toString()).contains("secret_in_package");
        assertThat(ok(get("/api/v1/device-catalogue/drivers/matrix?modelCode=MD-027"), ADMIN, null).toString()).contains("1.1.0");
        problem(post("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.1.0/revoke"), ADMIN, Map.of("reason", "x"), 403);
        assertThat(ok(post("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.1.0/revoke"), SECOPS, Map.of("reason", "key compromise"))
                .path("status").asText()).isEqualTo("REVOKED");
        assertThat(lookup().driverPackage("vitals.welchallyn.csm-pcd01", "1.1.0")).isEmpty();
        assertThat(lookup().driverPackage("vitals.welchallyn.csm-pcd01", "1.0.0")).isPresent();
    }

    // ------------------------------------------------------------------ posture, lifecycle, request-a-model, negative roles

    @Test
    void blockedPostureNeedsASecurityApprovedExceptionAndModelRequestsAreTracked() throws Exception {
        JsonNode posture = ok(put("/api/v1/device-catalogue/models/MD-003/posture"), SECOPS, Map.of("mds2", Map.of("received", true),
                "osSupported", false, "osName", "Windows 7", "patchPath", "VENDOR", "remoteSupport", "BROKERED", "supportStatus", "SUPPORTED"));
        assertThat(posture.path("posture").asText()).isEqualTo("BLOCKED");
        JsonNode blocked = problem(post("/api/v1/device-configurations"), ADMIN, Map.of("modelCode", "MD-003"), 422);
        assertThat(errorCodes(blocked)).contains("posture_blocked");
        problem(post("/api/v1/device-catalogue/models/MD-003/posture/exceptions"), ADMIN, Map.of("compensatingControls", List.of("ENHANCED_MONITORING"),
                "expiresOn", LocalDate.now().plusMonths(6).toString(), "riskOwner", "CISO"), 422);
        JsonNode ex = ok(post("/api/v1/device-catalogue/models/MD-003/posture/exceptions"), ADMIN, Map.of("compensatingControls",
                List.of("GATEWAY_ISOLATION_DEVICE_VLAN", "OUTBOUND_ONLY_DEVICE_HOST_AGENT"), "expiresOn", LocalDate.now().plusMonths(6).toString(),
                "riskOwner", "CISO"));
        String exId = ex.path("exceptionId").asText();
        problem(post("/api/v1/device-catalogue/models/MD-003/posture/exceptions/" + exId + "/approve"), ADMIN, null, 403);
        assertThat(ok(post("/api/v1/device-catalogue/models/MD-003/posture/exceptions/" + exId + "/approve"), SECOPS, null).path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(ok(get("/api/v1/device-catalogue/models/MD-003"), ADMIN, null).path("security").path("posture").asText()).isEqualTo("EXCEPTION_APPROVED");
        ok(post("/api/v1/device-configurations"), ADMIN, Map.of("modelCode", "MD-003"));
        assertThat(problem(post("/api/v1/device-catalogue/models/MD-003/lifecycle"), APPROVER, Map.of("to", "CERTIFIED"), 422).path("code").asText())
                .isEqualTo("no_certified_driver");
        problem(post("/api/v1/device-catalogue/models/MD-001/lifecycle"), ADMIN, Map.of("to", "RETIRED"), 422);
        ok(post("/api/v1/device-catalogue/models/MD-001/lifecycle"), ADMIN, Map.of("to", "DEPRECATED", "reason", "successor model"));
        assertThat(problem(post("/api/v1/device-catalogue/models/MD-001/lifecycle"), ADMIN, Map.of("to", "RETIRED"), 409).path("code").asText())
                .isEqualTo("published_instances");

        // UDI import with source and date
        JsonNode udi = ok(post("/api/v1/device-catalogue/models/MD-037/udi"), ADMIN, Map.of("source", "GUDID", "deviceIdentifier", "04987654321098",
                "record", Map.of("productCodes", List.of("DXN"), "premarketSubmission", "K000000"), "retrievedOn", LocalDate.now().toString()));
        assertThat(udi.path("udi").path("issuingAgency").asText()).isEqualTo("GS1");
        assertThat(udi.path("regulatory").get(0).path("reference").asText()).isEqualTo("K000000");

        // request-a-new-model with scoping and KPI
        JsonNode req = ok(post("/api/v1/device-model-requests"), ADMIN, Map.of("manufacturer", "Synthetic Instruments", "model", "SpiroX 9",
                "family", "SPIROMETRY", "connectivity", "USB, HL7 export", "sites", List.of("LHR"), "urgency", "HIGH"));
        assertThat(req.path("requestRef").asText()).startsWith("DMR-");
        problem(put("/api/v1/device-model-requests/" + req.path("id").asText() + "/scoping"), ADMIN, Map.of("status", "SCOPED"), 403);
        JsonNode scoped = ok(put("/api/v1/device-model-requests/" + req.path("requestRef").asText() + "/scoping"), TESTER, Map.of("existingFamilyAdapter",
                true, "adapter", "FILE_GDT", "effortDays", 3, "targetSprint", "S14", "status", "SCOPED"));
        assertThat(scoped.path("status").asText()).isEqualTo("SCOPED");
        assertThat(scoped.path("scopingWorkingDays").asInt()).isZero();
        assertThat(ok(get("/api/v1/device-model-requests/kpi"), ADMIN, null).path("medianScopingWorkingDays").asDouble()).isZero();

        // negative role paths: no person or console data for the wrong roles
        problem(get("/api/v1/device-configurations"), EMPLOYEE, null, 403);
        problem(get("/api/v1/device-configurations"), NURSE, null, 403);
        problem(post("/api/v1/device-configurations"), APPROVER, Map.of("modelCode", "MD-027"), 403);
        problem(post("/api/v1/device-configurations"), TESTER, Map.of("modelCode", "MD-027", "siteCode", "LHR"), 403);
        ok(post("/api/v1/device-configurations"), TESTER, Map.of("modelCode", "MD-027", "siteCode", "TEST"));
        problem(get("/api/v1/device-configurations/CFG-999999"), ADMIN, null, 404);
        problem(get("/api/v1/workspace/device-fleet"), EMPLOYEE, null, 403);
    }
}
