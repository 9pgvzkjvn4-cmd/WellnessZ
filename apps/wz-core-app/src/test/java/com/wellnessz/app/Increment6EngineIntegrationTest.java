package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSecretStore;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverPackage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 6 – E31 engine end to end against PostgreSQL: one path per protocol family (HL7 v2 PCD-01 with ACK, FHIR, POCT1-A,
 * GDT, CSV, XML with PDF pairing, BLE GATT, serial, vendor webhook with HMAC, DICOMweb + attachment, OCR fall-back, batch
 * store-and-forward), idempotency, association (vendor id, session, unmatched queue and resolution), gates (E14 calibration
 * lockout, QC, consent, plausibility), routing into the E03 / E07 / E10 / E12 sinks, dead letter and replay without duplicates,
 * tenant isolation and the operations API. E32 (catalogue) and E34 (authentication, signatures, secrets) are stood in for by
 * test beans implementing the kernel SPIs – the engine sees exactly the SPI contract.
 */
class Increment6EngineIntegrationTest extends IntegrationTestBase {

    static final String THUMB = "ab".repeat(32);
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor ADMIN = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
    static final JwtRequestPostProcessor TESTER = user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER");
    static final KeyPair SIGNING = keyPair();
    static final Map<String, DeviceCaptureSessions.SessionBinding> SESSIONS = new ConcurrentHashMap<>();
    static final Map<String, String> QC = new ConcurrentHashMap<>();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired DeviceObservationGateway gateway;

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ E32 / E34 stand-ins (kernel SPIs only)

    @TestConfiguration
    static class DeviceSpiStandIns {

        static DeviceCatalogueLookup.DeviceInstance instance(String id, String pkg, String version, DeviceCodes.Family family, String option,
                                                             String endpoint, String equipment, String serial, boolean verify, Map<String, Object> params,
                                                             DeviceCodes.Category category) {
            return new DeviceCatalogueLookup.DeviceInstance(id, "MD-T", family, "Synthetic", pkg, serial, null, "1.0", equipment,
                    DeviceCodes.Option.ofCode(option), "PUBLISHED", "LHR", "LHR-OH-ROOM-1", endpoint, pkg, version, Set.of(category), params,
                    List.of("WORKLIST", "SESSION", "BADGE", "VENDOR_ID_MATCH"), verify, List.of(), "Europe/London", "GROUP", 3);
        }

        static final Map<String, DeviceCatalogueLookup.DeviceInstance> INSTANCES = Map.ofEntries(
                Map.entry("DEV-T-VIT", instance("DEV-T-VIT", "vitals.welchallyn.csm-pcd01", "1.0.0", DeviceCodes.Family.VITALS, "P4", "EP-T-GW", null,
                        "CSM-100-4411", true, Map.of(), DeviceCodes.Category.VITALS)),
                Map.entry("DEV-T-SPI", instance("DEV-T-SPI", "spiro.ndd.easyone-connect-gdt", "1.2.0", DeviceCodes.Family.SPIROMETRY, "P2", "EP-T-AGT",
                        "SPIRO-HQ-01", "SP2-2001", true, Map.of(), DeviceCodes.Category.SPIROMETRY)),
                Map.entry("DEV-T-SPI2", instance("DEV-T-SPI2", "spiro.ndd.easyone-connect-gdt", "1.2.0", DeviceCodes.Family.SPIROMETRY, "P2", "EP-T-AGT",
                        "AUD-LHR-02", "SP-LOCKED", true, Map.of(), DeviceCodes.Category.SPIROMETRY)),
                Map.entry("DEV-T-AUD", instance("DEV-T-AUD", "audio.benson.cca200-db", "1.0.0", DeviceCodes.Family.AUDIOMETRY, "P2", "EP-T-AGT",
                        "AUD-LHR-01", "CCA-7781", true, Map.of(), DeviceCodes.Category.AUDIOMETRY)),
                Map.entry("DEV-T-ECG", instance("DEV-T-ECG", "ecg.ge.muse-xml", "1.0.0", DeviceCodes.Family.CARDIOLOGY, "P2", "EP-T-AGT", null, "MAC-1", true,
                        Map.of(), DeviceCodes.Category.ECG)),
                Map.entry("DEV-T-BLE", instance("DEV-T-BLE", "vitals.and.ua651ble-blp", "1.0.0", DeviceCodes.Family.VITALS, "P3", "EP-T-TAB", null, "BLE-1",
                        true, Map.of(), DeviceCodes.Category.VITALS)),
                Map.entry("DEV-T-BRE", instance("DEV-T-BRE", "da.lifeloc.fc20-serial", "1.0.0", DeviceCodes.Family.DRUG_ALCOHOL, "P1", "EP-T-AGT", null,
                        "FC20-30112", true, Map.of(), DeviceCodes.Category.BREATH_ALCOHOL)),
                Map.entry("DEV-T-POC", instance("DEV-T-POC", "poc.abbott.afinion2-poct1a", "1.0.0", DeviceCodes.Family.POC, "P5", "EP-T-DM", null,
                        "AFN2-55120", true, Map.of(), DeviceCodes.Category.POC)),
                Map.entry("DEV-T-WEAR", instance("DEV-T-WEAR", "wear.aggregator.fhir-pcha", "1.0.0", DeviceCodes.Family.WEARABLE, "P8", "EP-T-GW", null,
                        null, false, Map.of(), DeviceCodes.Category.WEARABLE)),
                Map.entry("DEV-T-FIT", instance("DEV-T-FIT", "fit.tsi.link-webhook", "1.0.0", DeviceCodes.Family.RESP_ERGONOMICS, "P6", null, null,
                        "PC8048-2201", true, Map.of("webhookSecretRef", "devsec:fit"), DeviceCodes.Category.FIT_TEST)),
                Map.entry("DEV-T-CXR", instance("DEV-T-CXR", "img.cxr.dicomweb", "1.0.0", DeviceCodes.Family.IMAGING, "P7", "EP-T-GW", null, null, true,
                        Map.of(), DeviceCodes.Category.IMAGING)),
                Map.entry("DEV-T-EXP", instance("DEV-T-EXP", "exp.casella.dbadge-csv", "1.0.0", DeviceCodes.Family.EXPOSURE, "P2", "EP-T-AGT", null,
                        "DB2-1188", false, Map.of("segCode", "LHR-RAMP-NOISE", "hazardCode", "NOISE"), DeviceCodes.Category.EXPOSURE)),
                Map.entry("DEV-T-EXP2", instance("DEV-T-EXP2", "exp.casella.dbadge-csv", "9.9.9", DeviceCodes.Family.EXPOSURE, "P2", "EP-T-AGT", null,
                        "DB2-1189", false, Map.of("segCode", "LHR-RAMP-NOISE", "hazardCode", "NOISE"), DeviceCodes.Category.EXPOSURE)),
                Map.entry("DEV-T-OCR", instance("DEV-T-OCR", "poc.magellan.leadcare2-ocr", "1.0.0", DeviceCodes.Family.POC, "P9", "EP-T-TAB", null, null,
                        true, Map.of(), DeviceCodes.Category.POC)));

        @Bean
        @Primary
        DeviceCatalogueLookup testCatalogue(BundledDrivers bundled) {
            return new DeviceCatalogueLookup() {
                @Override
                public Optional<DeviceInstance> instance(String id) {
                    return Optional.ofNullable(INSTANCES.get(id));
                }

                @Override
                public List<DeviceInstance> instancesForEndpoint(String endpointId, String serial) {
                    return INSTANCES.values().stream().filter(i -> endpointId.equals(i.endpointId())).toList();
                }

                @Override
                public List<DeviceInstance> instancesAtStation(String site, String station, DeviceCodes.Category category) {
                    return List.of();
                }

                @Override
                public Optional<DriverPackage> driverPackage(String packageId, String version) {
                    return bundled.get(packageId, version).map(p -> {
                        try {
                            Signature s = Signature.getInstance("Ed25519");
                            s.initSign(SIGNING.getPrivate());
                            s.update(p.canonicalJson().getBytes(StandardCharsets.UTF_8));
                            return new DriverPackage(p.packageId(), p.version(), p.family(), p.canonicalJson(), p.sha256(), "pkg-test-2026",
                                    Base64.getEncoder().encodeToString(s.sign()));
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }

                @Override
                public Optional<FormBinding> binding(String instanceId, String formCode) {
                    return Optional.empty();
                }

                @Override
                public QcStatus qcStatus(String instanceId, LocalDate on, String lot) {
                    return new QcStatus(QC.getOrDefault(instanceId, "VALID"), "TEST", null, null, lot, null);
                }
            };
        }

        @Bean
        @Primary
        DeviceEndpointAuthenticator testAuthenticator() {
            return c -> {
                if (c.token() == null || !c.token().equals("tok-" + c.endpointId())) {
                    return DeviceEndpointAuthenticator.Result.failure("bad_token");
                }
                String tenant = c.endpointId().equals("EP-T-PARTNER") ? "PARTNER" : "GROUP";
                DeviceCodes.EndpointKind kind = c.endpointId().equals("EP-T-TAB") ? DeviceCodes.EndpointKind.MOBILE_APP
                        : c.endpointId().equals("EP-T-AGT") ? DeviceCodes.EndpointKind.DEVICE_HOST_AGENT : DeviceCodes.EndpointKind.GATEWAY;
                return DeviceEndpointAuthenticator.Result.success(new DeviceEndpointPrincipal(c.endpointId(), kind, "LHR", "GATEWAY_ZONE", tenant,
                        Set.of(), c.certificateSha256()));
            };
        }

        @Bean
        @Primary
        DevicePackageSignatures testSignatures() {
            return new DevicePackageSignatures() {
                @Override
                public Verification verify(byte[] canonical, String sig, String keyId) {
                    try {
                        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
                        s.initVerify(SIGNING.getPublic());
                        s.update(canonical);
                        boolean ok = "pkg-test-2026".equals(keyId) && s.verify(Base64.getDecoder().decode(sig));
                        return new Verification(ok, keyId, "Ed25519", ok ? null : "signature_invalid");
                    } catch (Exception e) {
                        return new Verification(false, keyId, "Ed25519", "signature_invalid");
                    }
                }

                @Override
                public com.wellnessz.casekernel.device.DevicePackageSignatures.Signature sign(String actor, byte[] canonical) {
                    try {
                        java.security.Signature s = java.security.Signature.getInstance("Ed25519");
                        s.initSign(SIGNING.getPrivate());
                        s.update(canonical);
                        return new com.wellnessz.casekernel.device.DevicePackageSignatures.Signature("pkg-test-2026", "Ed25519",
                                Base64.getEncoder().encodeToString(s.sign()));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
            };
        }

        @Bean
        @Primary
        DeviceSecretStore testSecrets() {
            return new DeviceSecretStore() {
                @Override
                public String store(String actor, String scope, String name, char[] value) {
                    return "devsec:" + name;
                }

                @Override
                public Optional<char[]> resolve(String reference, String purpose) {
                    return "devsec:fit".equals(reference) ? Optional.of("fit-secret".toCharArray()) : Optional.empty();
                }

                @Override
                public void destroy(String actor, String reference) {
                }

                @Override
                public void rotate(String actor, String reference, char[] newValue) {
                }
            };
        }

        @Bean
        @Primary
        DeviceCaptureSessions testSessions() {
            return (instanceId, at) -> Optional.ofNullable(SESSIONS.get(instanceId));
        }
    }

    // ------------------------------------------------------------------ helpers

    MockHttpServletRequestBuilder device(String path, String endpoint, String instance, String key) {
        MockHttpServletRequestBuilder b = post("/api/v1/device-ingest/" + path).header("X-Device-Endpoint", endpoint)
                .header("Authorization", "DeviceToken tok-" + endpoint).header("X-Client-Cert-Sha256", THUMB).header("Idempotency-Key", key);
        return instance == null ? b : b.header("X-Device-Instance", instance);
    }

    byte[] golden(String path) throws Exception {
        try (var in = getClass().getResourceAsStream("/e31/golden/" + path)) {
            return in.readAllBytes();
        }
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    UUID onlyObservation(MvcResult r) throws Exception {
        JsonNode ids = body(r).get("observationIds");
        assertThat(ids).isNotNull();
        return UUID.fromString(ids.get(0).asString());
    }

    String obsStatus(UUID observationId) {
        return jdbc.queryForObject("select status from deviceint.observation where id = ?", String.class, observationId);
    }

    static BigDecimal num(Map<String, ?> fields, String key) {
        return new BigDecimal(String.valueOf(fields.get(key)));
    }

    static String key() {
        return "T31:" + UUID.randomUUID();
    }

    static void session(String instance, String employee, String orderRef) {
        SESSIONS.put(instance, new DeviceCaptureSessions.SessionBinding(UUID.randomUUID(), employee, null, orderRef, null, null, "nurse1", "LHR",
                "LHR-OH-ROOM-1", "FORM", null, null));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void hl7Pcd01AckIdempotencyVerificationAndRoutingIntoE03() throws Exception {
        byte[] hl7 = golden("vitals/csm-pcd01/normal.hl7");
        mvc.perform(post("/api/v1/device-ingest/hl7v2").header("X-Device-Endpoint", "EP-T-GW").header("Authorization", "DeviceToken tok-EP-T-GW")
                .contentType(MediaType.TEXT_PLAIN).content(hl7)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("mtls_required"));
        mvc.perform(post("/api/v1/device-ingest/hl7v2").header("X-Device-Endpoint", "EP-T-GW").header("Authorization", "DeviceToken wrong")
                .header("X-Client-Cert-Sha256", THUMB).contentType(MediaType.TEXT_PLAIN).content(hl7)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("bad_token"));
        String key = key();
        MvcResult r = mvc.perform(device("hl7v2", "EP-T-GW", "DEV-T-VIT", key).contentType("application/hl7-v2").content(hl7))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("MSA|AA|MSG0001");
        UUID messageId = jdbc.queryForObject("select id from deviceint.message where idempotency_key = ?", UUID.class, key);
        UUID obs = jdbc.queryForObject("select id from deviceint.observation where message_id = ?", UUID.class, messageId);
        assertThat(obsStatus(obs)).isEqualTo("PENDING_VERIFICATION");
        assertThat(jdbc.queryForMap("select employee_id, association_method, confidence::text as c from deviceint.observation where id = ?", obs))
                .containsEntry("employee_id", "E1001").containsEntry("association_method", "VENDOR_ID_MATCH").containsEntry("c", "0.97");
        // field encryption at rest and the canonical FHIR resources (Device, Observation, Provenance)
        assertThat(jdbc.queryForObject("select canonical from deviceint.observation where id = ?", String.class, obs)).startsWith("v1:");
        assertThat(jdbc.queryForList("select resource_type from deviceint.fhir_resource where observation_id = ? order by 1", String.class, obs))
                .containsExactly("Observation", "Provenance");
        assertThat(jdbc.queryForObject("select count(*) from deviceint.fhir_resource where resource_type = 'Device' and resource_id = 'DEV-T-VIT'",
                Integer.class)).isEqualTo(1);
        // same idempotency key → duplicate, original ACK, no second message
        MvcResult dup = mvc.perform(device("hl7v2", "EP-T-GW", "DEV-T-VIT", key).contentType("application/hl7-v2").content(hl7))
                .andExpect(status().isOk()).andReturn();
        assertThat(dup.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(dup.getResponse().getContentAsString()).contains("MSA|AA");
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where idempotency_key = ?", Integer.class, key)).isEqualTo(1);
        // preview, clinician acceptance → E03 result with device provenance; values never edited, a second accept is refused
        assertThat(num(gateway.preview(obs).orElseThrow().fields(), "measurements.systolic")).isEqualByComparingTo("124");
        DeviceObservationSink.Delivery d = gateway.accept("nurse1", obs, null, null);
        assertThat(d.status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(jdbc.queryForMap("select category, source, capture_method, verified_by, driver_version, flag from ohrecord.result where device_observation_id = ?", obs))
                .containsEntry("category", "VITALS").containsEntry("source", "DEVICE").containsEntry("capture_method", "DEVICE")
                .containsEntry("verified_by", "nurse1").containsEntry("driver_version", "vitals.welchallyn.csm-pcd01@1.0.0").containsEntry("flag", "NORMAL");
        assertThat(obsStatus(obs)).isEqualTo("ROUTED");
        assertThat(jdbc.queryForObject("select status from deviceint.fhir_resource where resource_type = 'Observation' and observation_id = ?", String.class, obs))
                .isEqualTo("final");
        assertThat(jdbc.queryForObject("select retention_class from deviceint.raw_object r join deviceint.message m on m.raw_object_id = r.id where m.id = ?",
                String.class, messageId)).isEqualTo("DEVICE_RAW_PAYLOAD");
        assertThatThrownBy(() -> gateway.accept("nurse1", obs, null, null)).isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("observation_not_pending");
        // person-level read: clinician sees values (audited), console roles are refused
        mvc.perform(get("/api/v1/device-engine/observations/" + obs + "?format=fhir").with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.components[?(@.key == 'systolic')].loincCode").value(org.hamcrest.Matchers.hasItem("8480-6"))).andExpect(jsonPath("$.fhir[0].resourceType").exists());
        mvc.perform(get("/api/v1/device-engine/observations?employeeId=E1001").with(ADMIN)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from platform.audit_event where action = 'DEVICE_OBSERVATION_READ'", Integer.class)).isPositive();
        // malformed message → AE, dead-lettered at PARSE
        MvcResult ae = mvc.perform(device("hl7v2", "EP-T-GW", "DEV-T-VIT", key()).contentType("application/hl7-v2")
                .content(golden("vitals/csm-pcd01/missing-pid.hl7"))).andExpect(status().isOk()).andReturn();
        assertThat(ae.getResponse().getContentAsString()).contains("MSA|AE");
        // implausible value → BLOCKED; a blocked result cannot be accepted
        MvcResult blocked = mvc.perform(device("hl7v2", "EP-T-GW", "DEV-T-VIT", key()).contentType("application/hl7-v2")
                .content(golden("vitals/csm-pcd01/sys-400.hl7"))).andExpect(status().isOk()).andReturn();
        assertThat(blocked.getResponse().getContentAsString()).contains("MSA|AA");
        UUID blockedObs = jdbc.queryForObject("""
                select o.id from deviceint.observation o join deviceint.message m on m.id = o.message_id where m.endpoint_id = 'EP-T-GW'
                 and o.flags_summary like '%implausible_systolic%' order by o.created_at desc limit 1""", UUID.class);
        assertThat(obsStatus(blockedObs)).isEqualTo("BLOCKED");
        assertThatThrownBy(() -> gateway.accept("nurse1", blockedObs, null, null)).isInstanceOf(ApiException.class).extracting("code")
                .isEqualTo("observation_blocked");
    }

    @Test
    void unknownDeviceTenantIsolationAndAuthorisationRules() throws Exception {
        byte[] hl7 = golden("vitals/csm-pcd01/normal.hl7");
        mvc.perform(device("hl7v2", "EP-T-PARTNER", "DEV-T-VIT", key()).contentType("application/hl7-v2").content(hl7))
                .andExpect(status().isOk()).andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("MSA|AR"));
        mvc.perform(device("fhir", "EP-T-PARTNER", "DEV-T-WEAR", key()).contentType("application/fhir+json").content(golden("wearable/fhir-pcha/normal.json")))
                .andExpect(status().isNotFound());
        mvc.perform(device("fhir", "EP-T-DM", "DEV-T-WEAR", key()).contentType("application/fhir+json").content(golden("wearable/fhir-pcha/normal.json")))
                .andExpect(status().isForbidden());
        mvc.perform(device("fhir", "EP-T-GW", "DEV-NOPE", key()).contentType("application/fhir+json").content(golden("wearable/fhir-pcha/normal.json")))
                .andExpect(status().isNotFound());
        // a device token cannot call the user API; a user token cannot call the ingest API
        mvc.perform(get("/api/v1/device-engine/gates").header("X-Device-Endpoint", "EP-T-GW").header("Authorization", "DeviceToken tok-EP-T-GW")
                .header("X-Client-Cert-Sha256", THUMB)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/device-ingest/fhir").with(NURSE).contentType("application/fhir+json").content("{}")).andExpect(status().isUnauthorized());
        // simulator traffic is not routed and stays out of person lists
        mvc.perform(post("/api/v1/device-engine/simulators/HL7V2/run").with(TESTER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasetId\":\"vitals/csm-pcd01/duplicate\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(1)).andExpect(jsonPath("$.duplicates").value(1));
        mvc.perform(post("/api/v1/device-engine/simulators/HL7V2/run").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"datasetId\":\"vitals/csm-pcd01/normal\"}")).andExpect(status().isForbidden());
    }

    @Test
    void fhirWearableConsentGateAndPoct1aAcknowledgement() throws Exception {
        MvcResult fhir = mvc.perform(device("fhir", "EP-T-GW", "DEV-T-WEAR", key()).contentType("application/fhir+json")
                .content(golden("wearable/fhir-pcha/normal.json"))).andExpect(status().isAccepted()).andReturn();
        UUID wear = onlyObservation(fhir);
        // the demo data holds an active WEARABLE_DATA consent for E1001: the reading is filed
        assertThat(jdbc.queryForObject("select flags_summary from deviceint.observation where id = ?", String.class, wear)).contains("CONSENT:PASS:consent_active");
        // without an active consent the reading is blocked (consent_missing) and not routed
        jdbc.update("update platform.consent set status = 'WITHDRAWN' where employee_id = 'E1001' and purpose = 'WEARABLE_DATA'");
        try {
            MvcResult again = mvc.perform(device("fhir", "EP-T-GW", "DEV-T-WEAR", key()).contentType("application/fhir+json")
                    .content(golden("wearable/fhir-pcha/normal.json"))).andExpect(status().isAccepted()).andReturn();
            UUID blocked = onlyObservation(again);
            assertThat(obsStatus(blocked)).isEqualTo("BLOCKED");
            assertThat(jdbc.queryForObject("select flags_summary from deviceint.observation where id = ?", String.class, blocked))
                    .contains("CONSENT:BLOCK:consent_missing");
        } finally {
            jdbc.update("update platform.consent set status = 'GRANTED' where employee_id = 'E1001' and purpose = 'WEARABLE_DATA'");
        }
        mvc.perform(device("fhir", "EP-T-GW", "DEV-T-WEAR", key()).contentType("application/fhir+json").content(golden("wearable/fhir-pcha/malformed.json")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.resourceType").value("OperationOutcome"));
        MvcResult poct = mvc.perform(device("poct1a", "EP-T-DM", "DEV-T-POC", key()).contentType(MediaType.APPLICATION_XML)
                .content(golden("poc/afinion2-poct1a/normal.xml"))).andExpect(status().isOk()).andReturn();
        assertThat(poct.getResponse().getContentAsString()).contains("ACK.type_cd V=\"AA\"").contains("ack_control_id V=\"1001\"");
        UUID poc = jdbc.queryForObject("""
                select o.id from deviceint.observation o join deviceint.message m on m.id = o.message_id where m.device_instance_id = 'DEV-T-POC'
                 order by o.created_at desc limit 1""", UUID.class);
        assertThat(obsStatus(poc)).isEqualTo("PENDING_VERIFICATION");
        assertThat(gateway.accept("nurse1", poc, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(jdbc.queryForObject("select category from ohrecord.result where device_observation_id = ?", String.class, poc)).isEqualTo("POC");
        MvcResult bad = mvc.perform(device("poct1a", "EP-T-DM", "DEV-T-POC", key()).contentType(MediaType.APPLICATION_XML)
                .content(golden("poc/afinion2-poct1a/malformed.xml"))).andExpect(status().isOk()).andReturn();
        assertThat(bad.getResponse().getContentAsString()).contains("ACK.type_cd V=\"AE\"");
    }

    @Test
    void fileExportsGdtWithPredictedValuesCalibrationLockoutCsvAndXmlWithPdfPairing() throws Exception {
        MvcResult gdt = mvc.perform(device("files", "EP-T-AGT", "DEV-T-SPI", key()).contentType("text/plain; charset=ISO-8859-1")
                .header("X-File-Name", "E1002_20261030.gdt").header("X-File-Format", "GDT").content(golden("spirometry/easyone-gdt/normal.gdt")))
                .andExpect(status().isAccepted()).andReturn();
        UUID spiro = onlyObservation(gdt);
        assertThat(obsStatus(spiro)).isEqualTo("PENDING_VERIFICATION");
        assertThat(gateway.preview(spiro).orElseThrow().observation().components()).anyMatch(c -> "fev1PredictedL".equals(c.key()));
        assertThat(jdbc.queryForList("select resource_type from deviceint.fhir_resource where observation_id = ? order by 1", String.class, spiro))
                .containsExactly("DiagnosticReport", "Observation", "Provenance");
        assertThat(gateway.accept("nurse1", spiro, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        String detail = jdbc.queryForObject("select detail from ohrecord.result where device_observation_id = ?", String.class, spiro);
        assertThat(detail).contains("fev1PercentPredicted");
        // E14 calibration lockout and QC lockout: the spirometer linked to an out-of-calibration register entry is blocked
        QC.put("DEV-T-SPI2", "DUE");
        MvcResult locked = mvc.perform(device("files", "EP-T-AGT", "DEV-T-SPI2", key()).contentType("text/plain; charset=ISO-8859-1")
                .header("X-File-Name", "E1002_lock.gdt").header("X-File-Format", "GDT").content(golden("spirometry/easyone-gdt/duplicate.gdt")))
                .andExpect(status().isAccepted()).andReturn();
        UUID lockedObs = onlyObservation(locked);
        assertThat(obsStatus(lockedObs)).isEqualTo("BLOCKED");
        assertThat(gateway.preview(lockedObs).orElseThrow().observation().qualityFlags())
                .anyMatch(f -> "device_out_of_calibration".equals(f.code()) && f.message().startsWith("Expired calibration"))
                .anyMatch(f -> "qc_due".equals(f.code()));
        mvc.perform(device("files", "EP-T-AGT", "DEV-T-SPI", key()).header("X-File-Name", "x.gdt").header("X-File-Format", "GDT")
                .header("X-File-Sha256", "00".repeat(32)).content(golden("spirometry/easyone-gdt/normal.gdt"))).andExpect(status().isBadRequest());
        // audiometry CSV from the vendor database view
        MvcResult csv = mvc.perform(device("files", "EP-T-AGT", "DEV-T-AUD", key()).contentType("text/csv").header("X-File-Name", "audiogram_1.csv")
                .header("X-File-Format", "CSV").content(golden("audiometry/cca200-db/normal.csv"))).andExpect(status().isAccepted()).andReturn();
        UUID audio = onlyObservation(csv);
        assertThat(gateway.accept("nurse1", audio, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(jdbc.queryForObject("select measurements from ohrecord.result where device_observation_id = ?", String.class, audio))
                .contains("ageCorrection").contains("\"4000\"");
        // ECG XML export, then its PDF (paired by file stem)
        MvcResult xml = mvc.perform(device("files", "EP-T-AGT", "DEV-T-ECG", key()).contentType(MediaType.APPLICATION_XML)
                .header("X-File-Name", "C:\\Muse\\ecg_7001.xml").header("X-File-Format", "XML").content(golden("cardiology/muse-xml/normal.xml")))
                .andExpect(status().isAccepted()).andReturn();
        UUID ecg = onlyObservation(xml);
        mvc.perform(device("files", "EP-T-AGT", "DEV-T-ECG", key()).contentType(MediaType.APPLICATION_PDF).header("X-File-Name", "ecg_7001.pdf")
                .header("X-File-Format", "PDF").content("%PDF-1.4 synthetic".getBytes(StandardCharsets.US_ASCII))).andExpect(status().isAccepted());
        assertThat(gateway.preview(ecg).orElseThrow().observation().attachments()).singleElement()
                .satisfies(a -> assertThat(a.kind()).isEqualTo("PDF"));
        assertThat(gateway.accept("physician1", ecg, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
    }

    @Test
    void bleWithSessionBatchStoreAndForwardAndSerialBreathAlcoholIntoE10() throws Exception {
        session("DEV-T-BLE", "E1001", null);
        MvcResult ble = mvc.perform(device("ble", "EP-T-TAB", "DEV-T-BLE", key()).contentType(MediaType.APPLICATION_JSON)
                .content(golden("vitals/ua651ble-blp/edge.json"))).andExpect(status().isAccepted()).andReturn();
        UUID bp = onlyObservation(ble);
        assertThat(jdbc.queryForObject("select association_method from deviceint.observation where id = ?", String.class, bp)).isEqualTo("SESSION");
        assertThat(num(gateway.preview(bp).orElseThrow().fields(), "measurements.systolic")).isEqualByComparingTo("122");   // kPa → mm[Hg]
        assertThat(gateway.accept("nurse1", bp, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        // store-and-forward: items out of order are processed in sequence order; a second replay creates no duplicates
        String reading = new String(golden("vitals/ua651ble-blp/normal.json"), StandardCharsets.UTF_8);
        String prefix = "EP-T-TAB:" + UUID.randomUUID() + ":";
        String batch = "{\"items\":[" + String.join(",", List.of(3, 1, 2).stream().map(i -> "{\"kind\":\"BLE\",\"idempotencyKey\":\"" + prefix + i
                + "\",\"sequence\":" + i + ",\"capturedAt\":\"2026-10-30T09:2" + i + ":00Z\",\"contentType\":\"application/json\",\"headers\":{\"X-Device-Instance\":\"DEV-T-BLE\"},\"body\":"
                + reading + "}").toList()) + "]}";
        MvcResult first = mvc.perform(device("batch", "EP-T-TAB", null, key()).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk()).andReturn();
        JsonNode results = body(first).get("results");
        assertThat(results).hasSize(3);
        assertThat(results.get(0).get("idempotencyKey").asString()).endsWith(":1");
        results.forEach(n -> assertThat(n.get("httpStatus").asInt()).isEqualTo(202));
        MvcResult again = mvc.perform(device("batch", "EP-T-TAB", null, key()).contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isOk()).andReturn();
        body(again).get("results").forEach(n -> assertThat(n.get("receipt").get("status").asString()).isEqualTo("DUPLICATE"));
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where idempotency_key like ?", Integer.class, prefix + "%")).isEqualTo(3);
        assertThat(jdbc.queryForList("select gateway_sequence from deviceint.message where idempotency_key like ? order by received_at", Long.class,
                prefix + "%")).containsExactly(1L, 2L, 3L);
        // serial breath alcohol for an open E10 test named by the collector's session (restricted class)
        UUID test = UUID.randomUUID();
        jdbc.update("""
                insert into substance.test (id, ref, employee_id, role_group, reason, panels, status, source_ref, due_at, alcohol_due_at, created_by, created_at, updated_at)
                values (?, ?, 'E1002', 'FLIGHT_CREW', 'RANDOM', 'ALCOHOL_BREATH', 'REQUIRED', ?, now() + interval '1 hour', now() + interval '1 hour', 'test', now(), now())""",
                test, "DAT-IT-" + test.toString().substring(0, 8), "test:" + test);
        session("DEV-T-BRE", "E1002", "DA:" + test);
        String frame = "{\"records\":[{\"port\":\"COM3\",\"receivedAt\":\"" + Instant.now() + "\",\"encoding\":\"TEXT\",\"data\":\"TEST#0099\\r\\nRESULT 0.000 g/210L\\r\\nTYPE SCREEN\\r\\nCAL 2026-10-01\\r\\nSN FC20-30112\\r\\n\"}]}";
        MvcResult serial = mvc.perform(device("serial", "EP-T-AGT", "DEV-T-BRE", key()).contentType(MediaType.APPLICATION_JSON).content(frame))
                .andExpect(status().isAccepted()).andReturn();
        UUID breath = onlyObservation(serial);
        assertThat(jdbc.queryForObject("select restricted from deviceint.observation where id = ?", Boolean.class, breath)).isTrue();
        mvc.perform(get("/api/v1/device-engine/observations/" + breath).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.restrictedNote").value("Restricted reading")).andExpect(jsonPath("$.components").doesNotExist());
        DeviceObservationSink.Delivery d = gateway.accept("collector1", breath, null, null);
        assertThat(d.status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(d.recordType()).isEqualTo("DA_TEST_READING");
        assertThat(jdbc.queryForObject("select breath_result from substance.test where id = ?", String.class, test)).isNotNull();
        SESSIONS.clear();
    }

    @Test
    void vendorWebhookHmacDicomwebAttachmentAndOcrFallback() throws Exception {
        byte[] payload = golden("resp-ergonomics/tsi-link/normal.json");
        String ts = Long.toString(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("fit-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.UTF_8));
        String sig = HexFormat.of().formatHex(mac.doFinal(payload));
        MvcResult ok = mvc.perform(post("/api/v1/device-webhooks/DEV-T-FIT").header("X-Timestamp", ts).header("X-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isAccepted()).andReturn();
        UUID messageId = UUID.fromString(body(ok).get("messageId").asString());
        UUID fit = jdbc.queryForObject("select id from deviceint.observation where message_id = ?", UUID.class, messageId);
        assertThat(gateway.accept("nurse1", fit, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(jdbc.queryForObject("select category from ohrecord.result where device_observation_id = ?", String.class, fit)).isEqualTo("FIT_TEST");
        byte[] tampered = new String(payload, StandardCharsets.UTF_8).replace("245", "999").getBytes(StandardCharsets.UTF_8);
        mvc.perform(post("/api/v1/device-webhooks/DEV-T-FIT").header("X-Timestamp", ts).header("X-Signature", sig).contentType(MediaType.APPLICATION_JSON)
                .content(tampered)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("signature_invalid"));
        mvc.perform(post("/api/v1/device-webhooks/DEV-T-FIT").header("X-Timestamp", Long.toString(Instant.now().getEpochSecond() - 3600))
                .header("X-Signature", sig).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("timestamp_out_of_window"));
        // with E34 deployed, security events go to its hash-chained store (the platform audit log is only the fallback without E34)
        assertThat(jdbc.queryForObject("select count(*) from devicesec.security_event where type = 'WEBHOOK_SIGNATURE_FAILED' and source_module = 'E31' "
                + "and device_instance_id = 'DEV-T-FIT' and reason_code in ('signature_invalid', 'timestamp_out_of_window')", Integer.class)).isGreaterThanOrEqualTo(2);
        // DICOM instance streamed first, then its metadata
        byte[] instance = "DICM synthetic chest image".getBytes(StandardCharsets.US_ASCII);
        MvcResult att = mvc.perform(device("attachments", "EP-T-GW", null, key()).contentType("application/dicom").header("X-Attachment-Kind", "DICOM")
                .header("X-Content-Sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(instance))).content(instance))
                .andExpect(status().isCreated()).andReturn();
        String archive = body(att).get("archiveId").asString();
        String meta = "{\"archiveId\":\"" + archive + "\",\"datasets\":" + new String(golden("imaging/cxr-dicomweb/normal.json"), StandardCharsets.UTF_8) + "}";
        MvcResult dcm = mvc.perform(device("dicomweb", "EP-T-GW", "DEV-T-CXR", key()).contentType("application/dicom+json").content(meta))
                .andExpect(status().isAccepted()).andReturn();
        UUID cxr = onlyObservation(dcm);
        assertThat(gateway.preview(cxr).orElseThrow().observation().attachments()).singleElement()
                .satisfies(a -> assertThat(a.archiveId()).hasToString(archive));
        mvc.perform(get("/api/v1/device-engine/archive/" + archive + "/verify").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.intact").value(true));
        // OCR-verified fall-back on a display-only analyser
        UUID ocr = gateway.submitFallback("nurse1", new DeviceObservationGateway.FallbackSubmission(null, DeviceCodes.CaptureMethod.OCR_VERIFIED,
                "DEV-T-OCR", null, null, null, null, DeviceCodes.Category.POC, "E1001", "E03.DEVICE_RESULT.POC",
                Map.of("bll", new BigDecimal("3.2"), "qcLot", "LC-2611"), null, new BigDecimal("0.97"), null));
        assertThat(obsStatus(ocr)).isEqualTo("PENDING_VERIFICATION");
        assertThat(gateway.accept("nurse1", ocr, null, null).status()).isEqualTo(DeviceObservationSink.Delivery.Status.DELIVERED);
        assertThat(jdbc.queryForObject("select capture_method from ohrecord.result where device_observation_id = ?", String.class, ocr)).isEqualTo("OCR_VERIFIED");
        // manual entry without attestation is blocked
        UUID manual = gateway.submitFallback("nurse1", new DeviceObservationGateway.FallbackSubmission(null, DeviceCodes.CaptureMethod.MANUAL_ATTESTED,
                null, null, null, null, null, DeviceCodes.Category.VISION, "E1001", null, Map.of("binocular", "6/6"), null, null, null));
        assertThat(obsStatus(manual)).isEqualTo("BLOCKED");
    }

    @Test
    void unmatchedQueueResolutionExposureRoutingDeadLetterReplayWithoutDuplicates() throws Exception {
        MvcResult r = mvc.perform(device("hl7v2", "EP-T-GW", "DEV-T-VIT", key()).contentType("application/hl7-v2")
                .content(golden("vitals/csm-pcd01/unknown-employee.hl7"))).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("MSA|AA");
        UUID obs = jdbc.queryForObject("""
                select o.id from deviceint.observation o join deviceint.message m on m.id = o.message_id where m.endpoint_id = 'EP-T-GW'
                 and o.status = 'UNMATCHED' order by o.created_at desc limit 1""", UUID.class);
        JsonNode queue = body(mvc.perform(get("/api/v1/device-engine/unmatched").with(NURSE)).andExpect(status().isOk()).andReturn());
        JsonNode item = null;
        for (JsonNode n : queue) {
            if (n.get("observationId").asString().equals(obs.toString())) {
                item = n;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("suggestions").get(0).get("employeeId").asString()).isEqualTo("E1001");
        assertThat(item.get("suggestions").get(0).get("confidence").decimalValue()).isEqualByComparingTo("0.92");
        mvc.perform(get("/api/v1/device-engine/unmatched/count").with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.open").isNumber());
        mvc.perform(get("/api/v1/device-engine/unmatched").with(ADMIN)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-engine/unmatched/" + item.get("id").asString() + "/resolve").with(NURSE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"employeeId\":\"E1001\",\"reasonCode\":\"CONFIRMED_IDENTITY\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStatus").value("PENDING_VERIFICATION"));
        assertThat(jdbc.queryForObject("select association_method from deviceint.observation where id = ?", String.class, obs)).isEqualTo("MANUAL_RESOLUTION");
        // exposure feed (no person): auto-routed into the E07 IH measurement import
        Instant before = Instant.now();
        MvcResult exp = mvc.perform(device("files", "EP-T-AGT", "DEV-T-EXP", key()).contentType("text/csv").header("X-File-Name", "dbadge_1.csv")
                .header("X-File-Format", "CSV").content(golden("exposure/dbadge-csv/normal.csv"))).andExpect(status().isAccepted()).andReturn();
        UUID expObs = onlyObservation(exp);
        assertThat(obsStatus(expObs)).isEqualTo("ROUTED");
        assertThat(jdbc.queryForObject("select count(*) from surveillance.ih_measurement where source_ref = ?", Integer.class, "device:" + expObs)).isEqualTo(1);
        // a driver version missing from the registry dead-letters deterministically; replay with the published version routes once
        MvcResult dead = mvc.perform(device("files", "EP-T-AGT", "DEV-T-EXP2", key()).contentType("text/csv").header("X-File-Name", "dbadge_9.csv")
                .header("X-File-Format", "CSV").content(golden("exposure/dbadge-csv/time-skew.csv"))).andExpect(status().isAccepted()).andReturn();
        UUID deadMessage = UUID.fromString(body(dead).get("messageId").asString());
        UUID dl = jdbc.queryForObject("select id from deviceint.dead_letter where message_id = ? and status = 'OPEN'", UUID.class, deadMessage);
        mvc.perform(get("/api/v1/device-engine/dead-letters?status=OPEN").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + dl + "')].code").value(org.hamcrest.Matchers.hasItem("no_driver_for_option")));
        mvc.perform(post("/api/v1/device-engine/dead-letters/" + dl + "/replay").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"driverVersion\":\"1.0.0\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PROCESSED"));
        UUID replayed = jdbc.queryForObject("select id from deviceint.observation where message_id = ?", UUID.class, deadMessage);
        assertThat(obsStatus(replayed)).isEqualTo("ROUTED");
        // replaying the whole range again produces no duplicates (deterministic ids, idempotent sinks)
        mvc.perform(post("/api/v1/device-engine/replay").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"from\":\"" + before.minusSeconds(5) + "\",\"to\":\"" + Instant.now().plusSeconds(5) + "\",\"deviceInstanceId\":\"DEV-T-EXP\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from surveillance.ih_measurement where source_ref = ?", Integer.class, "device:" + expObs)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deviceint.observation where message_id = (select message_id from deviceint.observation where id = ?)",
                Integer.class, expObs)).isEqualTo(1);
        // operations API (PHI-free) and the gate catalogue
        mvc.perform(get("/api/v1/device-engine/ops/connectors").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.deviceInstanceId == 'DEV-T-VIT')].state").value(org.hamcrest.Matchers.hasItem("HEALTHY")));
        mvc.perform(get("/api/v1/device-engine/ops/metrics?window=P1D").with(ADMIN)).andExpect(status().isOk()).andExpect(jsonPath("$.messages").isNumber());
        mvc.perform(get("/api/v1/device-engine/ops/alerts").with(ADMIN)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/device-engine/gates").with(NURSE)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(15));
        mvc.perform(get("/api/v1/device-engine/terminology/predicted?age=40&sex=M&heightCm=175").with(TESTER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.FEV1.predicted").isNumber());
        mvc.perform(get("/api/v1/device-engine/golden-datasets?family=VITALS").with(TESTER)).andExpect(status().isOk());
        String sample = json.writeValueAsString(body(mvc.perform(get("/api/v1/device-engine/drivers/vitals.welchallyn.csm-pcd01/1.0.0").with(TESTER))
                .andExpect(status().isOk()).andReturn()));
        mvc.perform(post("/api/v1/device-engine/drivers/validate").with(TESTER).contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true));
        mvc.perform(post("/api/v1/device-engine/drivers/conformance").with(TESTER).contentType(MediaType.APPLICATION_JSON).content(sample))
                .andExpect(status().isOk()).andExpect(jsonPath("$.passed").value(true));
        // gateway configuration and heartbeat
        mvc.perform(get("/api/v1/device-ingest/config").header("X-Device-Endpoint", "EP-T-GW").header("Authorization", "DeviceToken tok-EP-T-GW")
                .header("X-Client-Cert-Sha256", THUMB)).andExpect(status().isOk()).andExpect(jsonPath("$.configVersion").value(3));
        mvc.perform(device("heartbeat", "EP-T-GW", null, key()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"gatewayVersion\":\"1.0.0\",\"configVersion\":2,\"queue\":{\"depth\":0,\"capacityPct\":3},\"devices\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.commands[0].type").value("RELOAD_CONFIG"));
    }
}
