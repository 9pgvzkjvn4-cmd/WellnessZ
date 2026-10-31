package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSecretStore;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher.DeviceSecurityEvent;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.ingest.DeviceEndpointAuthentication;
import com.wellnessz.e34.DeviceSecurityJobs;
import com.wellnessz.e34.E34DemoSeeder;
import com.wellnessz.e34.signing.Canonicaliser;
import com.wellnessz.integration.outbox.OutboxEvent;
import com.wellnessz.integration.outbox.OutboxRepository;
import com.wellnessz.masterdata.ConfigService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 6 – E34 endpoint & integration security against a real PostgreSQL: endpoint identities and zero-touch enrolment with
 * a throw-away device CA (keytool), fail-closed authentication (thumbprint, token, status, zone / CIDR, path per endpoint type),
 * rotation and certificate renewal, anomaly rules with automatic isolation inside the 15-minute SLA and the re-certification
 * gate, Ed25519 package signatures, write-only connector secrets, the hash-chained security event log with SIEM / ITSM outbox
 * allow-lists, governance records and the disabled-module guard.
 */
class Increment6SecurityIntegrationTest extends IntegrationTestBase {

    static final DeviceTestPki PKI = DeviceTestPki.create();
    static final AtomicInteger ADDRESSES = new AtomicInteger(1);
    static final String CERT_HEADER = "X-Client-Cert-Sha256";
    static final Set<String> SIEM_FIELDS = Set.of("messageId", "eventId", "type", "severity", "endpointId", "deviceInstanceId", "siteCode", "zone",
            "sourceModule", "reasonCode", "actor", "remoteAddress", "protocol", "port", "rate", "keyId", "chainHash", "eventType", "occurredAt",
            "aggregateType", "aggregateId");
    static final Set<String> ITSM_FIELDS = Set.of("messageId", "ticketRef", "deviceInstanceId", "siteCode", "alertType", "severity", "raisedAt",
            "eventType", "occurredAt", "aggregateType", "aggregateId");

    static final List<String> STATUS_CALLS = Collections.synchronizedList(new ArrayList<>());

    @DynamicPropertySource
    static void devicePki(DynamicPropertyRegistry registry) {
        registry.add("wellnessz.device.pki.trust-anchors", PKI::caPemPath);
    }

    @TestConfiguration
    static class RecordingListener {
        @Bean
        DeviceEndpointStatusListener recordingEndpointStatusListener() {
            return (endpointId, instances, status, reason) -> STATUS_CALLS.add(endpointId + "|" + status + "|" + new java.util.TreeSet<>(instances) + "|" + reason);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired OutboxRepository outbox;
    @Autowired DeviceSpis spis;
    @Autowired DeviceSecretStore secretStore;
    @Autowired DevicePackageSignatures signatures;
    @Autowired DeviceEndpointProvisioning provisioning;
    @Autowired DeviceSecurityEventPublisher publisher;
    @Autowired DeviceSecurityJobs jobs;
    @Autowired ConfigService config;

    // ------------------------------------------------------------------ helpers

    static RequestPostProcessor secops() {
        return user("secops1", "S8904", "/AIRLINE", "SECURITY_OPS");
    }

    static RequestPostProcessor from(String address) {
        return r -> {
            r.setRemoteAddr(address);
            return r;
        };
    }

    static String nextAddress() {
        int n = ADDRESSES.getAndIncrement();
        return "10.77." + (n / 250) + "." + (n % 250 + 1);
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** Creates an endpoint as SECURITY_OPS and returns {endpointId, enrolmentToken}. */
    String[] create(EndpointKind kind, String site, String extraJson) throws Exception {
        String payload = "{\"kind\":\"" + kind + "\",\"siteCode\":\"" + site + "\"" + (extraJson == null ? "" : "," + extraJson) + "}";
        MvcResult r = mvc.perform(post("/api/v1/device-security/endpoints").with(secops()).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        JsonNode b = body(r);
        return new String[] {b.get("endpointId").asString(), b.has("enrolmentToken") ? b.get("enrolmentToken").asString() : null};
    }

    MockHttpServletRequestBuilder enrolRequest(String token, EndpointKind kind, DeviceTestPki.Client cert, String headerThumb, String proof) {
        String body = json.writeValueAsString(Map.of("enrolmentToken", token, "endpointKind", kind.name(), "certificatePem", cert.pem(),
                "proof", proof, "hostname", "gw.test.local", "platform", Map.of("os", "Linux", "osVersion", "Debian 12", "agentVersion", "0.6.0"),
                "hardening", Map.of("baseline", "CIS-L1-2026.1", "status", "PASS")));
        MockHttpServletRequestBuilder b = post("/api/v1/device-enrolment/enrol").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(from(nextAddress()));
        return headerThumb == null ? b : b.header(CERT_HEADER, headerThumb);
    }

    /** Successful enrolment; returns the endpoint token. */
    String enrol(String token, EndpointKind kind, DeviceTestPki.Client cert) throws Exception {
        MvcResult r = mvc.perform(enrolRequest(token, kind, cert, cert.sha256(), cert.sign("enrol:" + token + ":" + cert.sha256())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.configUrl").value("/api/v1/device-ingest/config"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        return body(r).get("endpointToken").asString();
    }

    /** Authenticated machine call (certificate renewal without body – passes authentication, then 400 at the controller). */
    org.springframework.test.web.servlet.ResultActions ingest(String endpointId, String token, String thumb, String address, String resource)
            throws Exception {
        MockHttpServletRequestBuilder b = post("/api/v1/device-ingest/" + resource).header("X-Device-Endpoint", endpointId)
                .header("Authorization", "DeviceToken " + token).contentType(MediaType.APPLICATION_JSON).content("{}").with(from(address));
        if (thumb != null) {
            b = b.header(CERT_HEADER, thumb);
        }
        return mvc.perform(b);
    }

    void expectAuthOk(String endpointId, String token, String thumb) throws Exception {
        ingest(endpointId, token, thumb, "10.20.0.1", "certificate-renewal").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("enrolment_invalid"));
    }

    void expectAuthFailure(String endpointId, String token, String thumb, String address, String resource, String code) throws Exception {
        ingest(endpointId, token, thumb, address, resource).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value(code));
    }

    int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    String endpointStatus(String id) {
        return jdbc.queryForObject("select status from devicesec.endpoint where endpoint_id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void endpointIdentityEnrolmentAndFailClosedAuthentication() throws Exception {
        // role gates
        mvc.perform(post("/api/v1/device-security/endpoints").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"GATEWAY\",\"siteCode\":\"LHR\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/device-security/endpoints").with(user("emp", "E1001", "/AIRLINE", "EMPLOYEE"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"GATEWAY\",\"siteCode\":\"LHR\"}")).andExpect(status().isForbidden());

        String[] ep = create(EndpointKind.GATEWAY, "LHR", "\"deviceInstanceIds\":[\"DEV-T-001\"]");
        String id = ep[0];
        String enrolmentToken = ep[1];
        assertThat(id).matches("EP-LHR-GW-\\d{2,}");
        assertThat(enrolmentToken).hasSize(43);
        // hashed at rest, never in plaintext; expiry at most 72 h
        assertThat(count("select count(*) from devicesec.enrolment_token where endpoint_id = ? and token_hash <> ?", id, enrolmentToken)).isEqualTo(1);
        assertThat(count("select count(*) from devicesec.enrolment_token where token_hash = ?", enrolmentToken)).isZero();
        Instant exp = jdbc.queryForObject("select expires_at from devicesec.enrolment_token where endpoint_id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat(exp).isBefore(Instant.now().plus(72, ChronoUnit.HOURS).plusSeconds(5));

        // DEVICE_ADMIN reads the inventory; the token is never shown again
        MvcResult list = mvc.perform(get("/api/v1/device-security/endpoints/" + id).with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING_ENROLMENT"))
                .andExpect(jsonPath("$.deviceInstanceIds[0]").value("DEV-T-001")).andReturn();
        assertThat(list.getResponse().getContentAsString()).doesNotContain(enrolmentToken).doesNotContain("tokenHash");
        expectAuthFailure(id, "anything", "ab".repeat(32), "10.20.0.1", "certificate-renewal", "endpoint_pending");

        DeviceTestPki.Client cert = PKI.client(id);
        DeviceTestPki.Client other = PKI.client("other");
        String proof = cert.sign("enrol:" + enrolmentToken + ":" + cert.sha256());
        // enrolment failures (each a security event ENROLMENT_FAILED)
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, cert, null, proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("mtls_required"));
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, cert, other.sha256(), proof))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("thumbprint_mismatch"));
        DeviceTestPki.Client untrusted = PKI.selfSigned("rogue");
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, untrusted, untrusted.sha256(),
                untrusted.sign("enrol:" + enrolmentToken + ":" + untrusted.sha256())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("certificate_untrusted"));
        DeviceTestPki.Client serverOnly = PKI.client("server-only", "serverAuth");
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, serverOnly, serverOnly.sha256(),
                serverOnly.sign("enrol:" + enrolmentToken + ":" + serverOnly.sha256())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("certificate_key_usage"));
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, cert, cert.sha256(), other.sign("enrol:" + enrolmentToken + ":" + cert.sha256())))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("proof_invalid"));
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.KIOSK, cert, cert.sha256(), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("enrolment_token_invalid"));
        mvc.perform(enrolRequest("x".repeat(43), EndpointKind.GATEWAY, cert, cert.sha256(), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("enrolment_token_invalid"));
        assertThat(count("select count(*) from devicesec.security_event where type = 'ENROLMENT_FAILED' and endpoint_id = ? "
                + "and reason_code in ('thumbprint_mismatch','certificate_untrusted','certificate_key_usage','proof_invalid','enrolment_token_invalid')", id))
                .isEqualTo(5);

        // zero-touch enrolment, then the one-time token cannot be replayed
        String token = enrol(enrolmentToken, EndpointKind.GATEWAY, cert);
        mvc.perform(enrolRequest(enrolmentToken, EndpointKind.GATEWAY, cert, cert.sha256(), proof))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("enrolment_token_invalid"));
        assertThat(endpointStatus(id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select cert_sha256 from devicesec.endpoint where endpoint_id = ?", String.class, id)).isEqualTo(cert.sha256());
        assertThat(count("select count(*) from devicesec.endpoint where endpoint_id = ? and token_hash = ?", id, token)).isZero();
        assertThat(count("select count(*) from platform.audit_event where action = 'DEVICE_ENROL' and resource_id = ?", id)).isEqualTo(1);

        // authentication: both factors, fail closed
        expectAuthOk(id, token, cert.sha256());
        expectAuthFailure(id, "wrong-token", cert.sha256(), "10.20.0.1", "certificate-renewal", "bad_token");
        expectAuthFailure(id, token, other.sha256(), "10.20.0.1", "certificate-renewal", "certificate_mismatch");
        expectAuthFailure(id, token, null, "10.20.0.1", "certificate-renewal", "mtls_required");
        expectAuthFailure("EP-NOPE-GW-99", token, cert.sha256(), "10.20.0.1", "certificate-renewal", "unknown_endpoint");
        expectAuthFailure(id, token, cert.sha256(), "203.0.113.9", "certificate-renewal", "zone_violation");
        // device credentials are useless on the user API
        mvc.perform(get("/api/v1/device-security/endpoints").header("X-Device-Endpoint", id).header("Authorization", "DeviceToken " + token)
                .header(CERT_HEADER, cert.sha256())).andExpect(status().isUnauthorized());

        // the failures are security events visible to SECURITY_OPS (and AUDITOR), not to DEVICE_ADMIN
        mvc.perform(get("/api/v1/device-security/endpoints/" + id + "/events").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'ENDPOINT_AUTH_FAILED' && @.reasonCode == 'bad_token')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'ENDPOINT_AUTH_FAILED' && @.reasonCode == 'zone_violation')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'ENROLMENT_SUCCEEDED')]").exists());
        mvc.perform(get("/api/v1/device-security/events").param("type", "ENDPOINT_AUTH_FAILED").with(user("auditor1", null, null, "AUDITOR")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/device-security/events").with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN")))
                .andExpect(status().isForbidden());
        // first authentication -> NEW_DEVICE_IDENTITY alert; zone violation -> alert; certificate mismatch -> alert
        mvc.perform(get("/api/v1/device-security/alerts").param("endpointId", id).with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ruleCode == 'NEW_DEVICE_IDENTITY')]").exists())
                .andExpect(jsonPath("$[?(@.ruleCode == 'ZONE_VIOLATION')]").exists())
                .andExpect(jsonPath("$[?(@.ruleCode == 'CERTIFICATE_ANOMALY')]").exists());

        // certificate renewal binds the new thumbprint; the old one is refused afterwards
        DeviceTestPki.Client renewed = PKI.client(id + "-2");
        String renewal = json.writeValueAsString(Map.of("certificatePem", renewed.pem(), "proof", renewed.sign("renew:" + id + ":" + renewed.sha256())));
        mvc.perform(post("/api/v1/device-ingest/certificate-renewal").header("X-Device-Endpoint", id).header("Authorization", "DeviceToken " + token)
                .header(CERT_HEADER, cert.sha256()).contentType(MediaType.APPLICATION_JSON).content(renewal).with(from("10.20.0.1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.certificate.sha256").value(renewed.sha256()));
        expectAuthFailure(id, token, cert.sha256(), "10.20.0.1", "certificate-renewal", "certificate_mismatch");
        expectAuthOk(id, token, renewed.sha256());

        // token rotation: new token returned once, previous token valid for the grace period only
        MvcResult rot = mvc.perform(post("/api/v1/device-security/endpoints/" + id + "/rotate-token").with(secops()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn();
        String newToken = body(rot).get("endpointToken").asString();
        expectAuthOk(id, newToken, renewed.sha256());
        expectAuthOk(id, token, renewed.sha256());
        jdbc.update("update devicesec.endpoint set prev_token_valid_until = now() - interval '1 minute' where endpoint_id = ?", id);
        expectAuthFailure(id, token, renewed.sha256(), "10.20.0.1", "certificate-renewal", "bad_token");
        expectAuthOk(id, newToken, renewed.sha256());
        // an expired endpoint token is refused
        jdbc.update("update devicesec.endpoint set token_expires_at = now() - interval '1 minute' where endpoint_id = ?", id);
        expectAuthFailure(id, newToken, renewed.sha256(), "10.20.0.1", "certificate-renewal", "token_expired");
    }

    @Test
    void expiredEnrolmentTokenAndReissue() throws Exception {
        String[] ep = create(EndpointKind.DEVICE_HOST_AGENT, "LHR", null);
        DeviceTestPki.Client cert = PKI.client(ep[0]);
        jdbc.update("update devicesec.enrolment_token set expires_at = now() - interval '1 second' where endpoint_id = ?", ep[0]);
        mvc.perform(enrolRequest(ep[1], EndpointKind.DEVICE_HOST_AGENT, cert, cert.sha256(), cert.sign("enrol:" + ep[1] + ":" + cert.sha256())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("enrolment_token_expired"));
        MvcResult re = mvc.perform(post("/api/v1/device-security/endpoints/" + ep[0] + "/enrolment-token").with(secops()))
                .andExpect(status().isOk()).andReturn();
        String fresh = body(re).get("enrolmentToken").asString();
        String token = enrol(fresh, EndpointKind.DEVICE_HOST_AGENT, cert);
        expectAuthOk(ep[0], token, cert.sha256());
        // a serial Device Host agent may not send FHIR (UNEXPECTED_PROTOCOL – rejected and alerted)
        expectAuthFailure(ep[0], token, cert.sha256(), "10.20.0.1", "fhir", "endpoint_forbidden");
        mvc.perform(get("/api/v1/device-security/alerts").param("endpointId", ep[0]).with(secops()))
                .andExpect(jsonPath("$[?(@.ruleCode == 'UNEXPECTED_PROTOCOL')]").exists());
        // re-issuing after enrolment is refused
        mvc.perform(post("/api/v1/device-security/endpoints/" + ep[0] + "/enrolment-token").with(secops()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_enrolled"));
    }

    @Test
    void zoningPoliciesAndConduitExport() throws Exception {
        // per-endpoint CIDR policy for a kiosk; kiosks only use the kiosk API
        String[] ep = create(EndpointKind.KIOSK, "LHR", "\"allowedCidrs\":[\"192.0.2.0/24\"]");
        DeviceTestPki.Client cert = PKI.client(ep[0]);
        String token = enrol(ep[1], EndpointKind.KIOSK, cert);
        expectAuthFailure(ep[0], token, cert.sha256(), "10.20.0.1", "certificate-renewal", "zone_violation");
        ingest(ep[0], token, cert.sha256(), "192.0.2.15", "certificate-renewal").andExpect(status().isBadRequest());
        expectAuthFailure(ep[0], token, cert.sha256(), "192.0.2.15", "hl7v2", "endpoint_forbidden");
        // devices in the device VLAN never reach the core directly
        mvc.perform(post("/api/v1/device-security/endpoints").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"DEVICE\",\"siteCode\":\"LHR\",\"zone\":\"DEVICE_VLAN\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("zone_violation"));
        // zones, conduits and the firewall policy template (deny by default)
        mvc.perform(get("/api/v1/device-security/zones").with(secops())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        mvc.perform(get("/api/v1/device-security/conduits").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'C-GW-CORE' && @.protocol == 'HTTPS_MTLS')]").exists());
        MvcResult csv = mvc.perform(get("/api/v1/device-security/conduits/export").param("format", "csv").with(secops()))
                .andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentAsString()).startsWith("rule,fromZone,toZone").contains("DEFAULT,*,*,*,*,*,DENY");
        mvc.perform(get("/api/v1/device-security/conduits/export").param("format", "json").with(secops())).andExpect(status().isOk());
        assertThat(count("select count(*) from devicesec.security_event where type = 'CONFIG_EXPORTED' and actor = 'secops1'")).isGreaterThanOrEqualTo(2);
        // address ranges are managed by SECURITY_OPS
        mvc.perform(post("/api/v1/device-security/zone-cidrs").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"siteCode\":\"DXB\",\"zone\":\"GATEWAY_ZONE\",\"cidr\":\"198.51.100.0/24\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/device-security/zone-cidrs").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"siteCode\":\"DXB\",\"zone\":\"GATEWAY_ZONE\",\"cidr\":\"not-a-range\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/device-security/zones").with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void failedAuthBurstIsolatesWithinSlaAndReleaseNeedsRecertification() throws Exception {
        String[] ep = create(EndpointKind.GATEWAY, "LHR", "\"deviceInstanceIds\":[\"DEV-REC-001\"]");
        String id = ep[0];
        DeviceTestPki.Client cert = PKI.client(id);
        String token = enrol(ep[1], EndpointKind.GATEWAY, cert);
        expectAuthOk(id, token, cert.sha256());
        STATUS_CALLS.clear();
        Instant start = Instant.now();
        for (int i = 0; i < 30; i++) {
            ingest(id, "guess-" + i, cert.sha256(), "10.30.0." + (i + 1), "certificate-renewal").andExpect(status().isUnauthorized());
        }
        // FAILED_AUTH_BURST: alert at 10, automatic isolation at 30 – authentication refused at once
        assertThat(endpointStatus(id)).isEqualTo("ISOLATED");
        expectAuthFailure(id, token, cert.sha256(), "10.20.0.1", "certificate-renewal", "endpoint_isolated");
        Map<String, Object> alert = jdbc.queryForMap("select id, severity, detected_at, isolation_due_at, isolated_at, ticket_ref from devicesec.alert "
                + "where rule_code = 'FAILED_AUTH_BURST' and subject_key = ?", id);
        Instant detected = ((java.sql.Timestamp) alert.get("detected_at")).toInstant();
        Instant isolated = ((java.sql.Timestamp) alert.get("isolated_at")).toInstant();
        assertThat(alert.get("severity")).isEqualTo("HIGH");
        assertThat(((java.sql.Timestamp) alert.get("isolation_due_at")).toInstant()).isEqualTo(detected.plus(15, ChronoUnit.MINUTES));
        assertThat(isolated).isAfterOrEqualTo(detected).isBefore(detected.plus(15, ChronoUnit.MINUTES));
        assertThat(isolated).isBefore(start.plus(5, ChronoUnit.MINUTES));
        assertThat((String) alert.get("ticket_ref")).startsWith("DSA-");
        // E31 / E32 are told after commit (instances to quarantine)
        assertThat(STATUS_CALLS).contains(id + "|ISOLATED|[DEV-REC-001]|ANOMALY_FAILED_AUTH_BURST");
        // incident with preserved evidence and the drill record
        String ref = jdbc.queryForObject("select incident_ref from devicesec.isolation where endpoint_id = ?", String.class, id);
        mvc.perform(get("/api/v1/device-security/incidents/" + ref).with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$.endpointId").value(id)).andExpect(jsonPath("$.withinSla").value(true))
                .andExpect(jsonPath("$.slaMinutes").value(15)).andExpect(jsonPath("$.evidence[0].type").value("EVENTS"))
                .andExpect(jsonPath("$.alertIds[0]").value(alert.get("id").toString())).andExpect(jsonPath("$.timeline.length()").value(2));
        assertThat(count("select count(*) from platform.notification where template_key = 'device.endpoint-isolated'")).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from devicesec.security_event where type = 'ENDPOINT_ISOLATED' and endpoint_id = ?", id)).isEqualTo(1);
        // re-certification gate: the instance is not re-certified (published) in the console
        mvc.perform(post("/api/v1/device-security/endpoints/" + id + "/release").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"RECERTIFIED\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("recertification_required"));
        mvc.perform(post("/api/v1/device-security/endpoints/" + id + "/release").with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN")))
                .andExpect(status().isForbidden());
        // the isolation sweep finds nothing left to do (idempotent)
        assertThat(count("select count(*) from devicesec.alert where isolation_due_at is not null and isolated_at is null and subject_key = ?", id)).isZero();

        // manual isolation / release / revocation of an endpoint without device instances
        String[] gw = create(EndpointKind.GATEWAY, "DXB", null);
        DeviceTestPki.Client gwCert = PKI.client(gw[0]);
        String gwToken = enrol(gw[1], EndpointKind.GATEWAY, gwCert);
        mvc.perform(post("/api/v1/device-security/endpoints/" + gw[0] + "/isolate").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"MANUAL_ISOLATION\",\"note\":\"drill\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ISOLATED")).andExpect(jsonPath("$.incidentRef").exists());
        expectAuthFailure(gw[0], gwToken, gwCert.sha256(), "10.20.0.1", "certificate-renewal", "endpoint_isolated");
        mvc.perform(post("/api/v1/device-security/endpoints/" + gw[0] + "/release").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        expectAuthOk(gw[0], gwToken, gwCert.sha256());
        mvc.perform(post("/api/v1/device-security/endpoints/" + gw[0] + "/revoke").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"DECOMMISSIONED\"}")).andExpect(status().isOk());
        expectAuthFailure(gw[0], gwToken, gwCert.sha256(), "10.20.0.1", "certificate-renewal", "endpoint_revoked");
        mvc.perform(post("/api/v1/device-security/endpoints/" + gw[0] + "/isolate").with(secops())).andExpect(status().isConflict());
        assertThat(STATUS_CALLS).contains(gw[0] + "|REVOKED|[]|DECOMMISSIONED");
        // a manual incident with raw-message evidence (referenced by archive id and hash, not copied)
        MvcResult inc = mvc.perform(post("/api/v1/device-security/incidents").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"endpointId\":\"" + id + "\",\"alertIds\":[\"" + alert.get("id") + "\"],\"summary\":\"Tabletop drill\","
                        + "\"evidence\":[{\"type\":\"RAW_MESSAGES\",\"sha256\":\"" + "a".repeat(64) + "\",\"archiveId\":\"ARC-1\"}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.incidentRef").value(org.hamcrest.Matchers.startsWith("DSI-"))).andReturn();
        String manualRef = body(inc).get("incidentRef").asString();
        mvc.perform(post("/api/v1/device-security/incidents/" + manualRef + "/close").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"Drill completed\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void driverPackageSignaturesAndKeyRotation() throws Exception {
        String manifest = "{\"schema\":\"wellnessz.device-driver/1\",\"packageId\":\"vitals.wa.connex\",\"version\":\"1.0.0\",\"family\":\"VITALS\","
                + "\"protocol\":{\"port\":2575,\"credentialRef\":\"devsec:6f1c2a3e-0000-4000-8000-000000000001\"},\"mappings\":[{\"key\":\"SPO2\",\"factor\":1.50}]}";
        RequestPostProcessor tester = user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER");
        mvc.perform(post("/api/v1/device-security/packages/sign").with(user("emp", "E1001", null, "EMPLOYEE")).contentType(MediaType.APPLICATION_JSON)
                .content(manifest)).andExpect(status().isForbidden());
        MvcResult signed = mvc.perform(post("/api/v1/device-security/packages/sign").with(tester).contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signature.algorithm").value("Ed25519")).andReturn();
        JsonNode sig = body(signed).get("signature");
        String withSig = manifest.substring(0, manifest.length() - 1) + ",\"signature\":" + json.writeValueAsString(sig) + "}";
        RequestPostProcessor admin = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
        mvc.perform(post("/api/v1/device-security/packages/verify").with(admin).contentType(MediaType.APPLICATION_JSON).content(withSig))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        // whitespace / key order do not matter, content does
        String reordered = "{ \"version\":\"1.0.0\", \"signature\":" + json.writeValueAsString(sig) + ", " + manifest.substring(1);
        mvc.perform(post("/api/v1/device-security/packages/verify").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(reordered.replace(",\"version\":\"1.0.0\"", ""))).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/device-security/packages/verify").with(admin).contentType(MediaType.APPLICATION_JSON)
                .content(withSig.replace("1.50", "1.5"))).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.failureCode").value("signature_invalid"));
        mvc.perform(post("/api/v1/device-security/packages/verify").with(admin).contentType(MediaType.APPLICATION_JSON).content(manifest))
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.failureCode").value("signature_missing"));
        assertThat(count("select count(*) from devicesec.security_event where type = 'PACKAGE_SIGNATURE_INVALID'")).isGreaterThanOrEqualTo(1);
        // packages with literal secrets are never signed
        mvc.perform(post("/api/v1/device-security/packages/sign").with(tester).contentType(MediaType.APPLICATION_JSON)
                .content("{\"packageId\":\"x.y\",\"protocol\":{\"password\":\"Summer2026!\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("secrets_in_package"));

        // rotation: the old key is RETIRED and still verifies; new signatures use the new key
        String oldKey = sig.get("keyId").asString();
        MvcResult rotated = mvc.perform(post("/api/v1/device-security/signing-keys").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"algorithm\":\"Ed25519\"}")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn();
        String newKey = body(rotated).get("keyId").asString();
        assertThat(newKey).isNotEqualTo(oldKey);
        mvc.perform(get("/api/v1/device-security/signing-keys").with(tester)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.keyId == '" + oldKey + "')].status").value("RETIRED"));
        byte[] canonical = Canonicaliser.canonical(manifest);
        assertThat(signatures.verify(canonical, sig.get("value").asString(), oldKey).valid()).isTrue();
        DevicePackageSignatures.Signature system = signatures.sign("system:test", canonical);
        assertThat(system.keyId()).isEqualTo(newKey);
        assertThat(signatures.verify(canonical, system.signatureBase64(), newKey).valid()).isTrue();
        assertThat(signatures.verify(canonical, system.signatureBase64(), "PKS-UNKNOWN").failureCode()).isEqualTo("unknown_key");
        // revocation: never verifies again; E32 is told in-process
        mvc.perform(post("/api/v1/device-security/signing-keys/" + oldKey + "/revoke").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(signatures.verify(canonical, sig.get("value").asString(), oldKey).failureCode()).isEqualTo("key_revoked");
        assertThat(count("select count(*) from platform.outbox_event where event_type = 'device.signing-key.revoked.v1' and aggregate_id = ?", oldKey))
                .isEqualTo(1);
        // private keys are encrypted at rest
        assertThat(jdbc.queryForList("select private_key from devicesec.signing_key", String.class)).allMatch(k -> k.startsWith("v1:"));
        // a person without a signer role cannot sign through the SPI either
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("nurse1", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_OH_CLINICIAN"))));
        try {
            assertThatThrownBy(() -> signatures.sign("nurse1", canonical)).hasMessageContaining("signing");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void connectorSecretsAreNeverReturned() throws Exception {
        String value = "Vendor-DB-Pass-7Qx!";
        MvcResult stored = mvc.perform(post("/api/v1/device-security/secrets").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"DEV-000002\",\"name\":\"audiometer vendor database\",\"value\":\"" + value + "\",\"boundInstances\":[\"DEV-000002\"]}"))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.reference").value(org.hamcrest.Matchers.startsWith("devsec:"))).andReturn();
        String ref = body(stored).get("reference").asString();
        assertThat(stored.getResponse().getContentAsString()).doesNotContain(value);
        MvcResult listed = mvc.perform(get("/api/v1/device-security/secrets").param("scope", "DEV-000002").with(secops()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value(ref)).andReturn();
        assertThat(listed.getResponse().getContentAsString()).doesNotContain(value).doesNotContain("ciphertext");
        mvc.perform(get("/api/v1/device-security/secrets").with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN"))).andExpect(status().isForbidden());
        String cipher = jdbc.queryForObject("select ciphertext from devicesec.secret where reference = ?", String.class, ref);
        assertThat(cipher).startsWith("v1:").doesNotContain(value);

        // runtime resolution (engine, no person) – audited with purpose
        assertThat(secretStore.resolve(ref, "VENDOR_DB_READ")).map(String::new).contains(value);
        assertThat(count("select count(*) from platform.audit_event where action = 'DEVICE_SECRET_RESOLVE' and resource_id = ? and outcome = 'SUCCESS'", ref))
                .isEqualTo(1);
        // a person in the security context never gets the value
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("secops1", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_SECURITY_OPS"))));
        try {
            assertThat(secretStore.resolve(ref, "VENDOR_DB_READ")).isEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
        // a gateway endpoint gets it only when bound to the secret's instances
        for (Object[] c : new Object[][] {{Set.of("DEV-000002"), true}, {Set.of("DEV-OTHER"), false}}) {
            @SuppressWarnings("unchecked")
            Set<String> instances = (Set<String>) c[0];
            SecurityContextHolder.getContext().setAuthentication(new DeviceEndpointAuthentication(new DeviceEndpointPrincipal("EP-LHR-AGT-01",
                    EndpointKind.DEVICE_HOST_AGENT, "LHR", "GATEWAY_ZONE", "GROUP", instances, "ab".repeat(32))));
            try {
                assertThat(secretStore.resolve(ref, "VENDOR_DB_READ").isPresent()).isEqualTo(c[1]);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
        // rotation keeps the reference
        mvc.perform(post("/api/v1/device-security/secrets/" + ref + "/rotate").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"Rotated-Pass-2\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.reference").value(ref))
                .andExpect(jsonPath("$.rotatedAt").exists());
        assertThat(secretStore.resolve(ref, "VENDOR_DB_READ")).map(String::new).contains("Rotated-Pass-2");
        // SPI store / destroy (E32)
        String spiRef = secretStore.store("deviceadmin1", "DEV-000004", "oauth client", "client-secret-x".toCharArray());
        secretStore.destroy("deviceadmin1", spiRef);
        assertThat(secretStore.resolve(spiRef, "CLOUD_POLL")).isEmpty();
        assertThat(secretStore.resolve("devsec:not-a-reference", "CLOUD_POLL")).isEmpty();
        assertThat(secretStore.resolve("vault:connectors/x", "CLOUD_POLL")).isEmpty(); // no vault mount configured
        // the shared secret scanner (console import, driver validation, test pack)
        mvc.perform(post("/api/v1/device-security/secret-scan").with(user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"db\":{\"password\":\"x\",\"secretRef\":\"" + ref + "\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].path").value("$.db.password"));
        // no plaintext anywhere in the security events
        assertThat(count("select count(*) from devicesec.security_event where attributes like ?", "%" + value + "%")).isZero();
    }

    @Test
    void eventChainSiemAndItsmAllowLists() throws Exception {
        // PHI-free by construction: unknown attribute keys are dropped
        publisher.publish(new DeviceSecurityEvent("UNKNOWN_DEVICE_IDENTITY", DeviceCodes.Severity.MEDIUM, "EP-LHR-GW-01", null, "LHR", "system", "E31",
                "unknown_serial", Map.of("deviceSerialHash", "c0ffee", "employeeId", "E1001", "value", "38.2"), Instant.now()));
        assertThat(count("select count(*) from devicesec.security_event where attributes like '%E1001%' or attributes like '%38.2%'")).isZero();
        mvc.perform(get("/api/v1/device-security/alerts").param("endpointId", "EP-LHR-GW-01").with(secops()))
                .andExpect(jsonPath("$[?(@.ruleCode == 'NEW_DEVICE_IDENTITY' && @.subjectKey == 'EP-LHR-GW-01:c0ffee')]").exists());

        // SIEM forwarding through the outbox with exactly the allow-listed flat fields
        List<OutboxEvent> siem = outbox.findAll().stream().filter(e -> "siem-device-security".equals(e.getDestination())).toList();
        assertThat(siem).isNotEmpty();
        assertThat(siem.size()).isEqualTo(count("select count(*) from devicesec.security_event"));
        for (OutboxEvent e : siem.subList(0, Math.min(siem.size(), 50))) {
            JsonNode p = json.readTree(e.getPayload());
            for (Iterator<String> it = p.propertyNames().iterator(); it.hasNext();) {
                String f = it.next();
                assertThat(SIEM_FIELDS).as(f).contains(f);
                assertThat(p.get(f).isObject() || p.get(f).isArray()).isFalse();
            }
            assertThat(p.get("eventType").asString()).isEqualTo("device.security.event.v1");
            assertThat(p.get("chainHash").asString()).hasSize(64);
        }
        // HIGH alerts raise an ITSM ticket (allow-list itsm-tickets)
        publisher.publish(new DeviceSecurityEvent("UNEXPECTED_PORT", DeviceCodes.Severity.HIGH, "EP-LHR-GW-01", null, null, "system", "E31",
                "listener_outside_conduits", Map.of("port", "23", "listener", "telnet"), Instant.now()));
        List<OutboxEvent> itsm = outbox.findAll().stream().filter(e -> "itsm-tickets".equals(e.getDestination())).toList();
        assertThat(itsm).isNotEmpty();
        for (OutboxEvent e : itsm) {
            JsonNode p = json.readTree(e.getPayload());
            p.propertyNames().forEach(f -> assertThat(ITSM_FIELDS).as(f).contains(f));
            assertThat(p.get("ticketRef").asString()).startsWith("DSA-");
        }
        // tamper evidence: intact chain, append-only table
        mvc.perform(get("/api/v1/device-security/events/verify").with(user("auditor1", null, null, "AUDITOR"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true)).andExpect(jsonPath("$.checked").value(org.hamcrest.Matchers.greaterThan(0)));
        assertThatThrownBy(() -> jdbc.update("update devicesec.security_event set reason_code = 'x' where id = (select min(id) from devicesec.security_event)"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("delete from devicesec.security_event")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void anomalyRulesAlertsAndEngineEvents() throws Exception {
        mvc.perform(get("/api/v1/device-security/anomaly-rules").with(secops())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(15))
                .andExpect(jsonPath("$[?(@.code == 'FAILED_AUTH_BURST')].isolateThreshold").value(30));
        mvc.perform(put("/api/v1/device-security/anomaly-rules/PAYLOAD_SCHEMA_ANOMALY").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"severity\":\"MEDIUM\",\"enabled\":true,\"threshold\":2,\"windowMinutes\":10,\"action\":\"ALERT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.threshold").value(2));
        mvc.perform(put("/api/v1/device-security/anomaly-rules/FAILED_AUTH_BURST").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"severity\":\"HIGH\",\"enabled\":true,\"threshold\":10,\"windowMinutes\":5,\"action\":\"ISOLATE\",\"isolateThreshold\":5}"))
                .andExpect(status().isBadRequest());
        String ep = "EP-LHR-TAB-01";
        for (int i = 0; i < 2; i++) {
            publisher.publish(new DeviceSecurityEvent("PAYLOAD_SCHEMA_ANOMALY", DeviceCodes.Severity.LOW, ep, null, null, "system", "E31", "parse_failed",
                    Map.of("schema", "HL7V2"), Instant.now()));
        }
        publisher.publish(new DeviceSecurityEvent("CLOCK_SKEW_DETECTED", DeviceCodes.Severity.LOW, ep, null, null, "system", "E31", "clock_skew",
                Map.of("skewMillis", "540000"), Instant.now()));
        publisher.publish(new DeviceSecurityEvent("HARDENING_DRIFT", DeviceCodes.Severity.MEDIUM, ep, null, null, "system", "E31", "drift",
                Map.of("baseline", "CIS-L1", "status", "DRIFT"), Instant.now()));
        for (int i = 0; i < 5; i++) {
            publisher.publish(new DeviceSecurityEvent("WEBHOOK_SIGNATURE_FAILED", DeviceCodes.Severity.MEDIUM, null, "DEV-WH-1", "LHR", "anonymous", "E31",
                    "signature_invalid", Map.of("connectorId", "DEV-WH-1"), Instant.now()));
        }
        MvcResult alerts = mvc.perform(get("/api/v1/device-security/alerts").param("status", "OPEN").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.ruleCode == 'PAYLOAD_SCHEMA_ANOMALY' && @.subjectKey == '" + ep + "')]").exists())
                .andExpect(jsonPath("$[?(@.ruleCode == 'CLOCK_SKEW' && @.subjectKey == '" + ep + "')]").exists())
                .andExpect(jsonPath("$[?(@.ruleCode == 'HARDENING_DRIFT' && @.subjectKey == '" + ep + "')]").exists())
                .andExpect(jsonPath("$[?(@.ruleCode == 'WEBHOOK_SIGNATURE_FAILURES' && @.subjectKey == 'DEV-WH-1')]").exists()).andReturn();
        assertThat(count("select count(*) from platform.notification where template_key = 'device.security-alert'")).isGreaterThanOrEqualTo(1);
        String alertId = null;
        for (JsonNode a : body(alerts)) {
            if ("HARDENING_DRIFT".equals(a.get("ruleCode").asString()) && ep.equals(a.get("subjectKey").asString())) {
                alertId = a.get("id").asString();
            }
        }
        mvc.perform(post("/api/v1/device-security/alerts/" + alertId + "/acknowledge").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(post("/api/v1/device-security/alerts/" + alertId + "/close").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resolution\":\"Baseline re-applied\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));

        // certificate expiry (demo gateway EP-DXB-GW-01 expires in 20 days)
        assertThat(jobs.certificateExpiry()).isGreaterThanOrEqualTo(1);
        assertThat(count("select count(*) from devicesec.alert where rule_code = 'CERTIFICATE_EXPIRING' and subject_key = 'EP-DXB-GW-01'")).isEqualTo(1);
        assertThat(count("select count(*) from platform.notification where template_key = 'device.certificate-expiring'")).isGreaterThanOrEqualTo(1);

        // message-rate spike: ceiling lowered to 5 per minute for the demo agent
        mvc.perform(put("/api/v1/device-security/anomaly-rules/MESSAGE_RATE_SPIKE").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"severity\":\"MEDIUM\",\"enabled\":true,\"threshold\":5,\"windowMinutes\":10,\"action\":\"THROTTLE\"}")).andExpect(status().isOk());
        try {
            String agent = "EP-LHR-AGT-01";
            for (int i = 0; i < 7; i++) {
                ingest(agent, E34DemoSeeder.demoToken(agent), E34DemoSeeder.demoThumbprint(agent), "10.20.0.1", "certificate-renewal");
            }
            assertThat(count("select count(*) from devicesec.security_event where type = 'MESSAGE_RATE_SPIKE' and endpoint_id = ?", agent)).isGreaterThanOrEqualTo(1);
            assertThat(count("select count(*) from devicesec.alert where rule_code = 'MESSAGE_RATE_SPIKE' and subject_key = ?", agent)).isEqualTo(1);
        } finally {
            mvc.perform(put("/api/v1/device-security/anomaly-rules/MESSAGE_RATE_SPIKE").with(secops()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"severity\":\"MEDIUM\",\"enabled\":true,\"threshold\":600,\"windowMinutes\":10,\"action\":\"THROTTLE\"}"));
        }
        assertThat(count("select count(*) from platform.audit_event where action = 'DEVICE_ANOMALY_RULE_UPDATE'")).isGreaterThanOrEqualTo(2);
    }

    @Test
    void provisioningSpiForTheConsole() {
        DeviceEndpointProvisioning.EndpointIssue tablet = provisioning.provision("deviceadmin1", new DeviceEndpointProvisioning.ProvisionRequest(
                "DEV-PROV-001", EndpointKind.MOBILE_APP, "LHR", null, "GROUP", null));
        assertThat(tablet.endpointId()).startsWith("EP-LHR-TAB-");
        assertThat(tablet.enrolmentToken()).isNotBlank();
        assertThat(tablet.status()).isEqualTo(DeviceCodes.EndpointStatus.PENDING_ENROLMENT);
        assertThat(tablet.toString()).doesNotContain(tablet.enrolmentToken());
        // a device behind an existing gateway: authorised on it, no token
        DeviceEndpointProvisioning.EndpointIssue behind = provisioning.provision("deviceadmin1", new DeviceEndpointProvisioning.ProvisionRequest(
                "DEV-PROV-002", EndpointKind.DEVICE, "LHR", null, "GROUP", "EP-LHR-GW-01"));
        assertThat(behind.endpointId()).isEqualTo("EP-LHR-GW-01");
        assertThat(behind.enrolmentToken()).isNull();
        assertThat(spis.provisioning().state("EP-LHR-GW-01").status()).isEqualTo(DeviceCodes.EndpointStatus.ACTIVE);
        // decommissioning one instance on a shared gateway removes only its authorisation
        STATUS_CALLS.clear();
        provisioning.revoke("deviceapprover1", "EP-LHR-GW-01", "DEV-PROV-002", "DECOMMISSIONED");
        assertThat(endpointStatus("EP-LHR-GW-01")).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from devicesec.endpoint_instance where endpoint_id = 'EP-LHR-GW-01' and device_instance_id = 'DEV-PROV-002'")).isZero();
        assertThat(STATUS_CALLS).contains("EP-LHR-GW-01|REVOKED|[DEV-PROV-002]|DECOMMISSIONED");
        // the only instance of an endpoint -> the identity is revoked
        provisioning.revoke("deviceapprover1", tablet.endpointId(), "DEV-PROV-001", "DECOMMISSIONED");
        assertThat(provisioning.state(tablet.endpointId()).status()).isEqualTo(DeviceCodes.EndpointStatus.REVOKED);
        assertThat(provisioning.state(tablet.endpointId()).recertificationRequired()).isTrue();
        // the demo identities authenticate with their documented test-only credentials
        assertThat(spis.authenticator().authenticate(new DeviceEndpointAuthenticator.Credentials("EP-LHR-GW-01", E34DemoSeeder.demoThumbprint("EP-LHR-GW-01"),
                E34DemoSeeder.demoToken("EP-LHR-GW-01"), "127.0.0.1", "POST", "/api/v1/device-ingest/hl7v2")).authenticated()).isTrue();
    }

    @Test
    void governanceRecordsAndGoLiveReadiness() throws Exception {
        RequestPostProcessor auditor = user("auditor1", null, null, "AUDITOR");
        mvc.perform(get("/api/v1/device-security/sdlc").with(auditor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.threatModels.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(9)))
                .andExpect(jsonPath("$.sbom.format").value("CycloneDX")).andExpect(jsonPath("$.disclosureUrl").exists());
        mvc.perform(post("/api/v1/device-security/sdlc/vulnerabilities").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"CVE-2099-0001\",\"component\":\"example-lib 1.0\",\"severity\":\"CRITICAL\",\"foundOn\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.dueOn").value(LocalDate.now().plusDays(7).toString()));
        mvc.perform(put("/api/v1/device-security/sdlc/vulnerabilities/CVE-2099-0001").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FIXED\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FIXED"));
        mvc.perform(post("/api/v1/device-security/pentest/findings").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ref\":\"PT-TEST-01\",\"severity\":\"CRITICAL\",\"title\":\"Test critical finding\",\"engagement\":\"PT-TEST\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/device-security/go-live-readiness").with(auditor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.checks[?(@.code == 'PENTEST_NO_OPEN_CRITICAL')].passed").value(false));
        mvc.perform(post("/api/v1/device-security/pentest/findings").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ref\":\"PT-TEST-01\",\"severity\":\"CRITICAL\",\"title\":\"Test critical finding\",\"status\":\"FIXED\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.closedOn").exists());
        // IEC 80001-1 agreement: signed only with a signatory per party
        mvc.perform(put("/api/v1/device-security/responsibility-agreement").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0\",\"reviewCadence\":\"ANNUAL\",\"signedOn\":\"" + LocalDate.now() + "\",\"parties\":[{\"party\":\"OH\",\"role\":\"owner\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/device-security/responsibility-agreement").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0\",\"reviewCadence\":\"ANNUAL\",\"signedOn\":\"" + LocalDate.now() + "\",\"parties\":["
                        + "{\"party\":\"Occupational Health unit\",\"role\":\"Responsible organisation\",\"signatory\":\"CMO\"},"
                        + "{\"party\":\"Group IT\",\"role\":\"Network operator\",\"signatory\":\"CIO\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.signedOn").value(LocalDate.now().toString()));
        // DPIA: only the DPO approves
        mvc.perform(post("/api/v1/device-security/privacy/dpia/approve").with(secops())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-security/privacy/dpia/approve").with(user("dpo1", null, null, "DPO"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // test-pack run recorded (CI stage / quarterly)
        mvc.perform(post("/api/v1/device-security/test-pack/runs").with(secops()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":\"1.0.0\",\"environment\":\"staging\",\"results\":[{\"script\":\"02_tls_mtls_check\",\"control\":\"SC-01\","
                        + "\"outcome\":\"PASS\",\"evidenceSha256\":\"" + "b".repeat(64) + "\"},{\"script\":\"14_rate_limit_and_anomaly_check\","
                        + "\"control\":\"SC-09\",\"outcome\":\"PASS\"}]}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passed").value(2)).andExpect(jsonPath("$.failed").value(0));
        mvc.perform(get("/api/v1/device-security/test-pack/runs").with(auditor)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].results.length()").value(2));
        mvc.perform(get("/api/v1/device-security/go-live-readiness").with(auditor)).andExpect(status().isOk())
                .andExpect(jsonPath("$.checks[?(@.code == 'PENTEST_NO_OPEN_CRITICAL')].passed").value(true))
                .andExpect(jsonPath("$.checks[?(@.code == 'IEC80001_AGREEMENT_SIGNED')].passed").value(true))
                .andExpect(jsonPath("$.checks[?(@.code == 'DPIA_APPROVED')].passed").value(true))
                .andExpect(jsonPath("$.checks[?(@.code == 'SECURITY_EVENT_CHAIN_INTACT')].passed").value(true));
        mvc.perform(get("/api/v1/device-security/compensating-controls").with(secops())).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'CC-LEGACY-PC-01')]").exists());
        mvc.perform(get("/api/v1/device-security/reports/access").with(auditor)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/device-security/privacy/dpia").with(user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void disabledModuleFailsClosed() throws Exception {
        config.put("modules.e34.enabled", "false", "E34", "test");
        try {
            mvc.perform(get("/api/v1/device-security/endpoints").with(secops())).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("module_disabled"));
            ingest("EP-LHR-GW-01", E34DemoSeeder.demoToken("EP-LHR-GW-01"), E34DemoSeeder.demoThumbprint("EP-LHR-GW-01"), "10.20.0.1",
                    "certificate-renewal").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("endpoint_security_unavailable"));
            assertThat(secretStore.resolve("devsec:00000000-0000-4000-8000-000000000000", "CLOUD_POLL")).isEqualTo(Optional.empty());
        } finally {
            config.put("modules.e34.enabled", "true", "E34", "test");
        }
        expectAuthOk("EP-LHR-GW-01", E34DemoSeeder.demoToken("EP-LHR-GW-01"), E34DemoSeeder.demoThumbprint("EP-LHR-GW-01"));
    }
}
