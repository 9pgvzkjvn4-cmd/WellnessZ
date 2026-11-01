package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.integration.outbox.OutboxRelay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 6 end to end across the four device modules with <b>no stand-ins</b> – every kernel device SPI is served by the real
 * module bean:
 * <ol>
 *   <li>E34 creates a gateway identity; the gateway enrols zero-touch (throw-away device CA) and heartbeats;</li>
 *   <li>E32 runs the eight-step wizard (simulator and LIVE tests through E31's diagnostics), four-eyes approval and publication,
 *       which authorises the instance on the gateway through E34's provisioning SPI;</li>
 *   <li>E34 authenticates the gateway on {@code /device-ingest} (both factors, fail closed, PHI-free security events);</li>
 *   <li>E33 opens a capture session; E31 ingests the HL7 v2 PCD-01 message, associates it through E33's session binding and
 *       notifies E33; the nurse previews and accepts;</li>
 *   <li>E31 routes into E03: a result with the device observation id, capture method, driver and verifier (provenance), backed by
 *       the engine's FHIR Provenance;</li>
 *   <li>E34 isolates the gateway: E32 quarantines the instance, E31 refuses it, E33 no longer offers it and E34 refuses the
 *       release until the instance is re-certified.</li>
 * </ol>
 */
class Increment6EndToEndIntegrationTest extends IntegrationTestBase {

    static final DeviceTestPki PKI = DeviceTestPki.create();
    static final String CERT_HEADER = "X-Client-Cert-Sha256";
    /** Inside the LHR gateway zone of the demo zoning (contract §15.4). */
    static final String GATEWAY_ADDRESS = "10.20.0.1";

    static final JwtRequestPostProcessor ADMIN = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
    static final JwtRequestPostProcessor APPROVER = user("deviceapprover1", "S8902", "/AIRLINE", "DEVICE_APPROVER");
    static final JwtRequestPostProcessor SECOPS = user("secops1", "S8904", "/AIRLINE", "SECURITY_OPS");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");

    @DynamicPropertySource
    static void devicePki(DynamicPropertyRegistry registry) {
        registry.add("wellnessz.device.pki.trust-anchors", PKI::caPemPath);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired DeviceSpis spis;
    @Autowired OutboxRelay relay;

    // ------------------------------------------------------------------ helpers

    static RequestPostProcessor from(String address) {
        return r -> {
            r.setRemoteAddr(address);
            return r;
        };
    }

    JsonNode body(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? json.readTree("{}") : json.readTree(s);
    }

    JsonNode call(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, Object payload, int expected) throws Exception {
        if (payload != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        MvcResult r = mvc.perform(req.with(who)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(expected);
        return body(r);
    }

    /** A machine call of the gateway: endpoint id, token and the client-certificate thumbprint the ingress forwards. */
    MockHttpServletRequestBuilder gateway(MockHttpServletRequestBuilder b, String endpointId, String token, String thumbprint) {
        b = b.header("X-Device-Endpoint", endpointId).header("Authorization", "DeviceToken " + token).with(from(GATEWAY_ADDRESS));
        return thumbprint == null ? b : b.header(CERT_HEADER, thumbprint);
    }

    JsonNode heartbeat(String endpointId, String token, String thumbprint, String deviceInstanceId) throws Exception {
        Map<String, Object> hb = new LinkedHashMap<>();
        hb.put("gatewayVersion", "0.6.0");
        hb.put("uptimeSeconds", 120);
        hb.put("queue", Map.of("depth", 0, "capacityPct", 0));
        hb.put("devices", deviceInstanceId == null ? List.of()
                : List.of(Map.of("deviceInstanceId", deviceInstanceId, "lastSeenAt", Instant.now().toString(), "state", "CONNECTED")));
        hb.put("hardening", Map.of("baseline", "CIS-L1-2026.1", "status", "PASS"));
        MvcResult r = mvc.perform(gateway(post("/api/v1/device-ingest/heartbeat"), endpointId, token, thumbprint)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(hb))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return body(r);
    }

    byte[] golden(String path) throws Exception {
        try (var in = getClass().getResourceAsStream("/e31/golden/" + path)) {
            return in.readAllBytes();
        }
    }

    int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    static String stepStatus(JsonNode cfg, int n) {
        return cfg.path("steps").get(n - 1).path("status").asString();
    }

    // ------------------------------------------------------------------ the path

    @Test
    void publishedConfigurationAuthenticatedIngestCaptureAndE03ResultThenIsolationQuarantines() throws Exception {
        // ---- E34: a new gateway identity, zero-touch enrolment, first heartbeat
        MvcResult created = mvc.perform(post("/api/v1/device-security/endpoints").with(SECOPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"GATEWAY\",\"siteCode\":\"LHR\"}")).andExpect(status().isCreated()).andReturn();
        String endpoint = body(created).path("endpointId").asString();
        String enrolment = body(created).path("enrolmentToken").asString();
        DeviceTestPki.Client cert = PKI.client(endpoint);
        String enrolBody = json.writeValueAsString(Map.of("enrolmentToken", enrolment, "endpointKind", "GATEWAY", "certificatePem", cert.pem(),
                "proof", cert.sign("enrol:" + enrolment + ":" + cert.sha256()), "hostname", "e2e-gw.test.local",
                "platform", Map.of("os", "Linux", "osVersion", "Debian 12", "agentVersion", "0.6.0"),
                "hardening", Map.of("baseline", "CIS-L1-2026.1", "status", "PASS")));
        MvcResult enrolled = mvc.perform(post("/api/v1/device-enrolment/enrol").header(CERT_HEADER, cert.sha256()).with(from(GATEWAY_ADDRESS))
                .contentType(MediaType.APPLICATION_JSON).content(enrolBody)).andExpect(status().isCreated()).andReturn();
        String token = body(enrolled).path("endpointToken").asString();
        assertThat(spis.provisioning().state(endpoint).status()).isEqualTo(DeviceCodes.EndpointStatus.ACTIVE);
        heartbeat(endpoint, token, cert.sha256(), null);

        // ---- E32: the eight-step wizard for a vital-signs monitor (HL7 v2 over MLLP) behind the new gateway
        JsonNode cfg = call(post("/api/v1/device-configurations"), ADMIN, Map.of("modelCode", "MD-027", "siteCode", "LHR"), 201);
        String id = cfg.path("configurationId").asString();
        String dev = cfg.path("deviceInstanceId").asString();
        cfg = call(put("/api/v1/device-configurations/" + id + "/steps/2"), ADMIN, Map.of("option", "P4"), 200);
        assertThat(cfg.path("driverPackageId").asString()).isEqualTo("vitals.welchallyn.csm-pcd01");
        cfg = call(put("/api/v1/device-configurations/" + id + "/steps/3"), ADMIN, Map.of("items", List.of(
                Map.of("code", "NETWORK_ZONE", "status", "DONE", "evidence", Map.of("reference", "DEVICE_VLAN", "text", "FW change CHG-2026-0999")),
                Map.of("code", "ENDPOINT_IDENTITY", "evidence", Map.of("reference", endpoint)),
                Map.of("code", "CERTIFICATES", "status", "DONE", "evidence", Map.of("reference", "PKI-REQ-9001")),
                Map.of("code", "CALIBRATION_EVIDENCE", "evidence", Map.of("documentId", "CAL-CERT-2026-901", "text", "Vendor calibration certificate")))), 200);
        // the endpoint auto-check asks E34 (real provisioning SPI): the enrolled gateway is ACTIVE
        assertThat(stepStatus(cfg, 3)).as(cfg.toString()).isEqualTo("VALID");
        assertThat(cfg.toString()).contains("Endpoint " + endpoint + " is ACTIVE");
        Map<String, Object> params = Map.of("deviceAddress", Map.of("ip", "10.20.30.99"), "mllp", Map.of("listenerEndpointId", endpoint),
                "msh", Map.of("sendingApplication", "CONNEX", "sendingFacility", "LHR_OH", "receivingFacility", "WZ"));
        cfg = call(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", params), 200);
        assertThat(stepStatus(cfg, 4)).isEqualTo("VALID");
        cfg = call(put("/api/v1/device-configurations/" + id + "/steps/5"), ADMIN, Map.of("associationMethods", List.of("SESSION"),
                "siteCode", "LHR", "stationCode", "LHR-OH-ROOM-1", "services", List.of("PERIODIC_MEDICAL"),
                "formCodes", List.of("E03.DEVICE_RESULT.VITALS")), 200);
        assertThat(stepStatus(cfg, 5)).isEqualTo("VALID");
        // step 6 through the real E31 diagnostics: golden-dataset simulator run (short dataset id as the console sends it) and the
        // LIVE connection test, which sees the gateway's heartbeat although the device itself is not published yet
        JsonNode sim = call(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "SIMULATOR", "datasetId", "normal"), 200);
        assertThat(sim.path("status").asString()).as(sim.toString()).isEqualTo("PASSED");
        assertThat(sim.toString()).contains("BINDING").doesNotContain("\"FAIL\"");
        heartbeat(endpoint, token, cert.sha256(), null);
        JsonNode live = call(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "LIVE"), 200);
        assertThat(live.path("status").asString()).as(live.toString()).isEqualTo("PASSED");
        cfg = call(post("/api/v1/device-configurations/" + id + "/steps/6/nurse-review"), NURSE, Map.of("outcome", "ACCEPTED", "note", "Values match"), 200);
        assertThat(stepStatus(cfg, 6)).as(cfg.toString()).isEqualTo("VALID");
        call(post("/api/v1/device-configurations/" + id + "/submit"), ADMIN, null, 200);
        assertThat(call(post("/api/v1/device-configurations/" + id + "/approve"), ADMIN, Map.of("note", "self"), 409).path("code").asString())
                .isEqualTo("four_eyes_violation");
        call(post("/api/v1/device-configurations/" + id + "/approve"), APPROVER, Map.of("note", "Evidence complete"), 200);
        assertThat(spis.catalogue().instance(dev)).isEmpty();
        JsonNode pub = call(post("/api/v1/device-configurations/" + id + "/publish"), ADMIN, null, 200);
        // publication authorised the instance on the enrolled gateway through the real E34 provisioning SPI – no new identity, no token
        assertThat(pub.path("endpointId").asString()).isEqualTo(endpoint);
        assertThat(pub.has("enrolmentToken")).isFalse();
        assertThat(count("select count(*) from devicesec.endpoint_instance where endpoint_id = ? and device_instance_id = ?", endpoint, dev)).isEqualTo(1);
        DeviceCatalogueLookup.DeviceInstance inst = spis.catalogue().instance(dev).orElseThrow();
        assertThat(inst.published()).isTrue();
        assertThat(inst.endpointId()).isEqualTo(endpoint);
        assertThat(inst.option()).isEqualTo(DeviceCodes.Option.P4_HL7_MLLP);
        // a published vital-signs monitor is locked out until its validation check is recorded (E32-HLR-04)
        assertThat(spis.catalogue().qcStatus(dev, LocalDate.now(), null).blocks()).isTrue();
        call(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", dev, "checkType", "BP_VALIDATION_INTERVAL", "outcome", "PASS"), 201);
        assertThat(spis.catalogue().qcStatus(dev, LocalDate.now(), null).verdict()).isEqualTo("VALID");
        // the gateway now reports the device (published instances only)
        heartbeat(endpoint, token, cert.sha256(), dev);
        assertThat(spis.diagnostics().health(dev).state()).isEqualTo("HEALTHY");

        // ---- E34 on /device-ingest: both factors, fail closed, one PHI-free security event per failure
        byte[] hl7 = golden("vitals/csm-pcd01/normal.hl7");
        mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, token, null).contentType("application/hl7-v2").content(hl7))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("mtls_required"));
        mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, "not-the-token", cert.sha256()).contentType("application/hl7-v2").content(hl7))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("bad_token"));
        mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, token, PKI.client("other").sha256()).contentType("application/hl7-v2")
                .content(hl7)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("certificate_mismatch"));
        assertThat(count("select count(*) from devicesec.security_event where type = 'ENDPOINT_AUTH_FAILED' and endpoint_id = ? "
                + "and reason_code in ('mtls_required','bad_token','certificate_mismatch')", endpoint)).isEqualTo(3);
        assertThat(count("select count(*) from devicesec.security_event where endpoint_id = ? and attributes like '%E1001%'", endpoint)).isZero();
        // a person's bearer token is not accepted on the machine path
        mvc.perform(post("/api/v1/device-ingest/hl7v2").with(NURSE).contentType("application/hl7-v2").content(hl7)).andExpect(status().isUnauthorized());

        // ---- E33: the nurse opens a capture session on the device (station binding for the engine's association)
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("formCode", "E03.DEVICE_RESULT.VITALS");
        open.put("formVersion", "1");
        open.put("employeeId", "E1001");
        open.put("deviceInstanceId", dev);
        open.put("siteCode", "LHR");
        open.put("stationCode", "LHR-OH-ROOM-1");
        JsonNode session = call(post("/api/v1/device-capture/sessions"), NURSE, open, 201);
        String sessionId = session.path("sessionId").asString();
        assertThat(session.path("state").asString()).as(session.toString()).isEqualTo("LISTENING");
        assertThat(spis.sessions().activeSession(dev, Instant.now())).isPresent();
        // E33's device.capture.session.opened.v1 is consumed by E31, which queues the worklist push for the gateway
        relay.relay();
        JsonNode commands = heartbeat(endpoint, token, cert.sha256(), dev).path("commands");
        List<String> types = new ArrayList<>();
        commands.forEach(c -> types.add(c.path("type").asString() + ":" + c.path("deviceInstanceId").asString()));
        assertThat(types).contains(session.path("contextPush").asString() + ":" + dev);

        // ---- E31: HL7 v2 PCD-01 through the authenticated gateway → ACK, associated through the E33 session
        String key = "E2E:" + UUID.randomUUID();
        MvcResult ack = mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, token, cert.sha256()).header("X-Device-Instance", dev)
                .header("Idempotency-Key", key).contentType("application/hl7-v2").content(hl7)).andReturn();
        assertThat(ack.getResponse().getStatus()).as(ack.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(ack.getResponse().getContentAsString()).contains("MSA|AA");
        UUID message = jdbc.queryForObject("select id from deviceint.message where idempotency_key = ?", UUID.class, key);
        UUID obs = jdbc.queryForObject("select id from deviceint.observation where message_id = ?", UUID.class, message);
        assertThat(jdbc.queryForMap("select employee_id, association_method, status, device_instance_id from deviceint.observation where id = ?", obs))
                .containsEntry("employee_id", "E1001").containsEntry("association_method", "SESSION").containsEntry("status", "PENDING_VERIFICATION")
                .containsEntry("device_instance_id", dev);

        // ---- E33: preview (pushed by E31's listener notice) and accept
        JsonNode view = call(get("/api/v1/device-capture/sessions/" + sessionId), NURSE, null, 200);
        assertThat(view.path("state").asString()).as(view.toString()).isEqualTo("PREVIEW");
        assertThat(view.path("observationId").asString(view.path("preview").path("observationId").asString())).isEqualTo(obs.toString());
        JsonNode preview = view.path("preview");
        assertThat(preview.path("acceptEnabled").asBoolean()).as(preview.toString()).isTrue();
        assertThat(preview.path("provenance").path("associationMethod").asString()).isEqualTo("SESSION");
        assertThat(preview.path("values").size()).isGreaterThanOrEqualTo(2);
        List<String> acknowledge = new ArrayList<>();
        preview.path("flags").forEach(f -> {
            if ("WARN".equals(f.path("outcome").asString())) {
                acknowledge.add(f.path("code").asString());
            }
        });
        JsonNode accepted = call(post("/api/v1/device-capture/sessions/" + sessionId + "/accept"), NURSE, Map.of("acknowledgedFlags", acknowledge), 200);
        assertThat(accepted.path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(accepted.path("routedRecordType").asString()).isEqualTo("RESULT");
        assertThat(spis.sessions().activeSession(dev, Instant.now())).isEmpty();

        // ---- E03: the result carries the device provenance; the engine holds the FHIR Provenance behind it
        Map<String, Object> result = jdbc.queryForMap("select id, employee_id, category, source, capture_method, verified_by, verified_at, driver_version, "
                + "device_model, recorded_by from ohrecord.result where device_observation_id = ?", obs);
        assertThat(result).containsEntry("employee_id", "E1001").containsEntry("category", "VITALS").containsEntry("source", "DEVICE")
                .containsEntry("capture_method", "DEVICE").containsEntry("verified_by", "nurse1");
        assertThat(result.get("verified_at")).isNotNull();
        assertThat(String.valueOf(result.get("driver_version"))).isEqualTo("vitals.welchallyn.csm-pcd01@1.0.0");
        assertThat(String.valueOf(result.get("id"))).isEqualTo(accepted.path("routedRecordId").asString(String.valueOf(result.get("id"))));
        assertThat(jdbc.queryForObject("select status from deviceint.observation where id = ?", String.class, obs)).isEqualTo("ROUTED");
        assertThat(jdbc.queryForList("select resource_type from deviceint.fhir_resource where observation_id = ?", String.class, obs)).contains("Provenance");
        JsonNode engineView = call(get("/api/v1/device-engine/observations/" + obs), NURSE, null, 200);
        assertThat(engineView.toString()).contains(dev).contains("vitals.welchallyn.csm-pcd01");
        assertThat(count("select count(*) from platform.outbox_event where event_type = 'device.observation.routed.v1' and aggregate_id = ?", obs.toString()))
                .isEqualTo(1);
        // the same message again: idempotent, no second observation or result
        mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, token, cert.sha256()).header("X-Device-Instance", dev)
                .header("Idempotency-Key", key).contentType("application/hl7-v2").content(hl7)).andExpect(status().isOk());
        assertThat(count("select count(*) from ohrecord.result where device_observation_id = ?", obs)).isEqualTo(1);
    }

    @Test
    void e34IsolationMakesE32QuarantineTheDeviceAndE31AndE33RefuseIt() throws Exception {
        // an enrolled gateway with a published instance (abbreviated path – the full wizard is covered above)
        MvcResult created = mvc.perform(post("/api/v1/device-security/endpoints").with(SECOPS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"GATEWAY\",\"siteCode\":\"LHR\"}")).andExpect(status().isCreated()).andReturn();
        String endpoint = body(created).path("endpointId").asString();
        String enrolment = body(created).path("enrolmentToken").asString();
        DeviceTestPki.Client cert = PKI.client(endpoint);
        MvcResult enrolled = mvc.perform(post("/api/v1/device-enrolment/enrol").header(CERT_HEADER, cert.sha256()).with(from(GATEWAY_ADDRESS))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enrolmentToken", enrolment, "endpointKind", "GATEWAY",
                        "certificatePem", cert.pem(), "proof", cert.sign("enrol:" + enrolment + ":" + cert.sha256()), "hostname", "iso-gw.test.local",
                        "platform", Map.of("os", "Linux", "osVersion", "Debian 12", "agentVersion", "0.6.0"),
                        "hardening", Map.of("baseline", "CIS-L1-2026.1", "status", "PASS"))))).andExpect(status().isCreated()).andReturn();
        String token = body(enrolled).path("endpointToken").asString();
        String dev = publishVitalsMonitor(endpoint, token, cert.sha256(), "LHR-OH-ROOM-2");
        call(post("/api/v1/device-calibration/checks"), NURSE, Map.of("deviceInstanceId", dev, "checkType", "BP_VALIDATION_INTERVAL", "outcome", "PASS"), 201);
        heartbeat(endpoint, token, cert.sha256(), dev);
        JsonNode devices = call(get("/api/v1/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-OH-ROOM-2"), NURSE, null, 200);
        assertThat(devices.toString()).contains(dev);

        // SECURITY_OPS isolates the gateway (playbook) → after commit E34 notifies E31 and E32
        JsonNode iso = call(post("/api/v1/device-security/endpoints/" + endpoint + "/isolate"), SECOPS,
                Map.of("reasonCode", "MANUAL_ISOLATION", "note", "suspected compromise"), 200);
        assertThat(iso.path("status").asString()).isEqualTo("ISOLATED");
        // E32: the instance is QUARANTINED (visible through the catalogue SPI), with a fleet alert and a history entry
        DeviceCatalogueLookup.DeviceInstance quarantined = spis.catalogue().instance(dev).orElseThrow();
        assertThat(quarantined.status()).isEqualTo("QUARANTINED");
        assertThat(quarantined.published()).isFalse();
        assertThat(count("select count(*) from devicecat.fleet_alert where device_instance_id = ? and alert_type = 'QUARANTINED' and status = 'OPEN'", dev))
                .isEqualTo(1);
        // E31: the connector is isolated
        assertThat(jdbc.queryForObject("select endpoint_status from deviceint.connector_state where device_instance_id = ?", String.class, dev))
                .isEqualTo("ISOLATED");
        assertThat(spis.diagnostics().health(dev).state()).isEqualTo("ISOLATED");
        // E34 refuses the isolated gateway on the ingest path
        mvc.perform(gateway(post("/api/v1/device-ingest/hl7v2"), endpoint, token, cert.sha256()).header("X-Device-Instance", dev)
                .header("Idempotency-Key", "E2E:" + UUID.randomUUID()).contentType("application/hl7-v2").content(golden("vitals/csm-pcd01/normal.hl7")))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("endpoint_isolated"));
        // E33: the device is no longer offered and a capture cannot be opened on it
        devices = call(get("/api/v1/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-OH-ROOM-2"), NURSE, null, 200);
        assertThat(devices.toString()).doesNotContain(dev);
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("formCode", "E03.DEVICE_RESULT.VITALS");
        open.put("formVersion", "1");
        open.put("employeeId", "E1002");
        open.put("deviceInstanceId", dev);
        open.put("siteCode", "LHR");
        open.put("stationCode", "LHR-OH-ROOM-2");
        MvcResult refused = mvc.perform(post("/api/v1/device-capture/sessions").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(open))).andReturn();
        assertThat(refused.getResponse().getStatus()).as(refused.getResponse().getContentAsString()).isBetween(400, 499);
        // release is gated on re-certification in the console (the instance is quarantined, not published)
        assertThat(call(post("/api/v1/device-security/endpoints/" + endpoint + "/release"), SECOPS, Map.of("reasonCode", "RELEASED"), 409)
                .path("code").asString()).isEqualTo("recertification_required");
        assertThat(spis.catalogue().instance(dev).orElseThrow().status()).isEqualTo("QUARANTINED");
    }

    @Test
    void signingKeyRevokedInE34RevokesItsPackagesInTheE32Registry() throws Exception {
        JwtRequestPostProcessor tester = user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER");
        // a dedicated signing key; a package signed by E34 and registered in E32 (E32 verifies it through E34 at import)
        String key = call(post("/api/v1/device-security/signing-keys"), SECOPS, Map.of("algorithm", "Ed25519"), 201).path("keyId").asString();
        JsonNode existing = call(get("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/1.0.0"), ADMIN, null, 200);
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = json.readValue(existing.path("manifest").toString(), Map.class);
        pkg.remove("signature");
        String version = "1.0." + (100 + (int) (Math.random() * 800));
        pkg.put("version", version);
        JsonNode signed = call(post("/api/v1/device-security/packages/sign"), tester, pkg, 200).path("signature");
        assertThat(signed.path("keyId").asString()).isEqualTo(key);
        pkg.put("signature", json.readValue(signed.toString(), Map.class));
        JsonNode reg = call(post("/api/v1/device-catalogue/drivers"), tester, Map.of("packageJson", pkg, "conformanceRunId", "CR-E2E",
                "conformanceEvidenceSha256", "ef".repeat(32)), 201);
        assertThat(reg.path("status").asString()).isEqualTo("REGISTERED");
        assertThat(spis.catalogue().driverPackage("vitals.welchallyn.csm-pcd01", version)).isPresent();

        // SECURITY_OPS revokes the key → device.signing-key.revoked.v1 → E32 revokes every package signed with it
        call(post("/api/v1/device-security/signing-keys/" + key + "/revoke"), SECOPS, null, 200);
        relay.relay();
        assertThat(call(get("/api/v1/device-catalogue/drivers/vitals.welchallyn.csm-pcd01/" + version), ADMIN, null, 200).path("status").asString())
                .isEqualTo("REVOKED");
        assertThat(spis.catalogue().driverPackage("vitals.welchallyn.csm-pcd01", version)).isEmpty();
        // packages signed with other keys are untouched
        assertThat(spis.catalogue().driverPackage("vitals.welchallyn.csm-pcd01", "1.0.0")).isPresent();
    }

    /** Wizard for a vital-signs monitor behind an enrolled gateway; returns the published device instance id. */
    String publishVitalsMonitor(String endpoint, String token, String thumbprint, String station) throws Exception {
        JsonNode cfg = call(post("/api/v1/device-configurations"), ADMIN, Map.of("modelCode", "MD-027", "siteCode", "LHR"), 201);
        String id = cfg.path("configurationId").asString();
        String dev = cfg.path("deviceInstanceId").asString();
        call(put("/api/v1/device-configurations/" + id + "/steps/2"), ADMIN, Map.of("option", "P4"), 200);
        call(put("/api/v1/device-configurations/" + id + "/steps/3"), ADMIN, Map.of("items", List.of(
                Map.of("code", "NETWORK_ZONE", "status", "DONE", "evidence", Map.of("reference", "DEVICE_VLAN")),
                Map.of("code", "ENDPOINT_IDENTITY", "evidence", Map.of("reference", endpoint)),
                Map.of("code", "CERTIFICATES", "status", "DONE", "evidence", Map.of("reference", "PKI-REQ-9002")),
                Map.of("code", "CALIBRATION_EVIDENCE", "evidence", Map.of("documentId", "CAL-CERT-2026-902", "text", "Vendor certificate")))), 200);
        call(put("/api/v1/device-configurations/" + id + "/steps/4"), ADMIN, Map.of("parameters", Map.of("deviceAddress", Map.of("ip", "10.20.30.98"),
                "mllp", Map.of("listenerEndpointId", endpoint), "msh", Map.of("sendingApplication", "CONNEX", "sendingFacility", "LHR_OH",
                        "receivingFacility", "WZ"))), 200);
        call(put("/api/v1/device-configurations/" + id + "/steps/5"), ADMIN, Map.of("associationMethods", List.of("SESSION"), "siteCode", "LHR",
                "stationCode", station, "formCodes", List.of("E03.DEVICE_RESULT.VITALS")), 200);
        call(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "SIMULATOR", "datasetId", "normal"), 200);
        heartbeat(endpoint, token, thumbprint, null);
        call(post("/api/v1/device-configurations/" + id + "/steps/6/tests"), ADMIN, Map.of("mode", "LIVE"), 200);
        call(post("/api/v1/device-configurations/" + id + "/steps/6/nurse-review"), NURSE, Map.of("outcome", "ACCEPTED"), 200);
        call(post("/api/v1/device-configurations/" + id + "/submit"), ADMIN, null, 200);
        call(post("/api/v1/device-configurations/" + id + "/approve"), APPROVER, null, 200);
        assertThat(call(post("/api/v1/device-configurations/" + id + "/publish"), ADMIN, null, 200).path("endpointId").asString()).isEqualTo(endpoint);
        return dev;
    }
}
