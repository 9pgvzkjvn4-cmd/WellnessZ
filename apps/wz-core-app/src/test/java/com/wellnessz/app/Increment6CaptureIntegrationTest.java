package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.Category;
import com.wellnessz.casekernel.device.DeviceCodes.GateOutcome;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.casekernel.device.DeviceObservationGateway;
import com.wellnessz.casekernel.device.DeviceObservationListener;
import com.wellnessz.casekernel.device.DeviceObservationSink;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.service.CaptureSessionService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Acceptance tests for E33 – module-embedded device capture (contract 14): form registry and station-aware device list,
 * capture session → device reading → live stream → preview with flags and provenance → accept → E03 result with
 * {@code device_observation_id} and {@code capture_method}; warnings, blocks and re-test; the fall-back chain (alternate device,
 * photo / OCR verified, manual attested); restricted breath-alcohol visibility; physician sign-off of ECG interpretations;
 * offline batch reconciliation (200 employees); kiosk self-measurement with badge and consent; employee summaries and wearable
 * consents; KPIs; expiry; module switch-off – including the negative paths.
 *
 * <p>E31 (engine), E32 (catalogue) and E34 (endpoint security) are built in parallel and are not on this branch: the test
 * supplies stub implementations of their kernel SPIs ({@link StubEngine} for {@code DeviceObservationGateway} – it associates
 * readings through E33's {@code DeviceCaptureSessions} and notifies E33's {@code DeviceObservationListener} as the engine does –
 * a stub {@code DeviceCatalogueLookup} and a stub {@code DeviceEndpointAuthenticator}). They are {@code @Primary}, so the test
 * keeps working once the real modules are merged. Accepting into {@code E03.RESULT} uses the E03 sink when it is deployed and
 * otherwise writes the provenance columns the sink sets.</p>
 */
class Increment6CaptureIntegrationTest extends IntegrationTestBase {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JsonMapper json;
    @Autowired
    StubEngine engine;
    @Autowired
    CaptureSessionService sessionService;
    @Autowired
    DeviceSpis spis;

    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor NURSE2 = user("nurse2", "N2", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PHYSICIAN = user("physician1", "P1", null, "OH_PHYSICIAN");
    static final JwtRequestPostProcessor NURSING_HEAD = user("nursinghead1", "NH1", null, "NURSING_HEAD");
    static final JwtRequestPostProcessor COLLECTOR = user("collector1", "C1", null, "COLLECTOR");
    static final JwtRequestPostProcessor COLLECTOR2 = user("collector2", "C2", null, "COLLECTOR");
    static final JwtRequestPostProcessor MRO = user("mro1", "MR1", null, "MRO");
    static final JwtRequestPostProcessor DEVICE_ADMIN = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
    static final JwtRequestPostProcessor MANAGER = user("manager1", "M2001", "/AIRLINE/CATERING", "MANAGER");
    static final JwtRequestPostProcessor ERIN = user("employee1", "E1001", "/AIRLINE/CATERING", "EMPLOYEE");
    static final JwtRequestPostProcessor CARA = user("crew1", "E1003", "/AIRLINE/OPS/CABIN", "EMPLOYEE");
    static final JwtRequestPostProcessor PAUL = user("pilot1", "E1002", "/AIRLINE/OPS/FLIGHTOPS", "EMPLOYEE");

    static final String THUMB = "ab".repeat(32);
    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    // ------------------------------------------------------------------ stubs of E31 / E32 / E34 kernel SPIs

    static DeviceCatalogueLookup.DeviceInstance inst(String id, String model, DeviceCodes.Family family, Category cat, String station,
                                                      String status, List<String> forms, String endpoint) {
        return new DeviceCatalogueLookup.DeviceInstance(id, "MD-027", family, "Synthetic Medical", model, "SN-" + id, null, "1.0", null,
                DeviceCodes.Option.P4_HL7_MLLP, status, "LHR", station, endpoint, "vitals.synthetic.pcd01", "1.0.0", Set.of(cat), Map.of(),
                List.of("SESSION"), true, forms, "Europe/London", "GROUP", 1);
    }

    static final Map<String, DeviceCatalogueLookup.DeviceInstance> INSTANCES = new LinkedHashMap<>();

    static {
        List<String> vitalsForms = List.of("E03.DEVICE_RESULT.VITALS", "E12.SCREENING_RESULT");
        for (DeviceCatalogueLookup.DeviceInstance i : List.of(
                inst("DEV-T-VITALS-1", "Connex Spot", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-OH-ROOM-1", "PUBLISHED", vitalsForms, "EP-T-GW-01"),
                inst("DEV-T-VITALS-2", "BLE BP cuff", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-OH-ROOM-1", "PUBLISHED", vitalsForms, "EP-T-TAB-01"),
                inst("DEV-T-VITALS-LOCK", "Connex Spot", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-OH-ROOM-1", "PUBLISHED", vitalsForms, "EP-T-GW-01"),
                inst("DEV-T-VITALS-QUAR", "Connex Spot", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-OH-ROOM-1", "QUARANTINED", vitalsForms, "EP-T-GW-01"),
                inst("DEV-T-SPIRO-1", "EasyOne Air", DeviceCodes.Family.SPIROMETRY, Category.SPIROMETRY, "LHR-OH-ROOM-2", "PUBLISHED",
                        List.of("E03.DEVICE_RESULT.SPIROMETRY"), "EP-T-AGT-01"),
                inst("DEV-T-ECG-1", "MAC 2000", DeviceCodes.Family.CARDIOLOGY, Category.ECG, "LHR-OH-ROOM-2", "PUBLISHED",
                        List.of("E03.DEVICE_RESULT.ECG"), "EP-T-GW-01"),
                inst("DEV-T-BREATH-1", "Alcotest 7510", DeviceCodes.Family.DRUG_ALCOHOL, Category.BREATH_ALCOHOL, "LHR-OH-ROOM-3", "PUBLISHED",
                        List.of("E10.BREATH_ALCOHOL"), "EP-T-AGT-01"),
                inst("DEV-T-EXPIRY-1", "Connex Spot", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-OH-ROOM-4", "PUBLISHED", vitalsForms, "EP-T-GW-01"),
                inst("DEV-T-BLE-1", "BLE BP cuff", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-SCREEN-1", "PUBLISHED", vitalsForms, "EP-T-TAB-01"),
                inst("DEV-T-SCALE-1", "BLE scale", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-SCREEN-1", "PUBLISHED", vitalsForms, "EP-T-TAB-01"),
                inst("DEV-T-BLE-200", "BLE BP cuff", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-SCREEN-2", "PUBLISHED", vitalsForms, "EP-T-TAB-02"),
                inst("DEV-T-KIOSK-BP", "Kiosk BP", DeviceCodes.Family.VITALS, Category.VITALS, "LHR-KIOSK-1", "PUBLISHED",
                        List.of("E12.SCREENING_RESULT"), "EP-T-KIOSK-01"))) {
            INSTANCES.put(i.deviceInstanceId(), i);
        }
    }

    /** E32 stand-in: published instances, one locked out by a due daily check, a driver manifest with worklist push. */
    static class StubCatalogue implements DeviceCatalogueLookup {
        @Override
        public Optional<DeviceInstance> instance(String id) {
            return Optional.ofNullable(INSTANCES.get(id));
        }

        @Override
        public List<DeviceInstance> instancesForEndpoint(String endpointId, String serial) {
            return INSTANCES.values().stream().filter(i -> endpointId.equals(i.endpointId())).toList();
        }

        @Override
        public List<DeviceInstance> instancesAtStation(String site, String station, Category category) {
            return INSTANCES.values().stream().filter(i -> site.equals(i.siteCode()) && station.equals(i.stationCode()))
                    .filter(i -> i.categories().contains(category)).toList();
        }

        @Override
        public Optional<DriverPackage> driverPackage(String id, String version) {
            return Optional.of(new DriverPackage(id, version, DeviceCodes.Family.VITALS,
                    "{\"capabilities\":{\"historicRetrieval\":false,\"startFromForm\":false,\"worklistPush\":\"HL7_ADT\"}}", "00", "k1", "sig"));
        }

        @Override
        public Optional<FormBinding> binding(String id, String formCode) {
            return Optional.empty();
        }

        @Override
        public QcStatus qcStatus(String id, LocalDate on, String lot) {
            return "DEV-T-VITALS-LOCK".equals(id) ? new QcStatus("DUE", "BP_VALIDATION_INTERVAL", null, null, null, null) : QcStatus.unknown();
        }
    }

    /**
     * E31 stand-in: associates readings with {@code DeviceCaptureSessions} (priority 2, SESSION), runs no gates of its own (the
     * test supplies the flags), notifies the observation listeners like the engine after commit, previews, accepts (routes),
     * rejects and accepts fall-back submissions. Kiosk and verify-before-post-off forms are auto-posted.
     */
    static class StubEngine implements DeviceObservationGateway {

        record Obs(DeviceObservation observation, String formCode, UUID sessionId, String status, boolean verify) {
        }

        final Map<UUID, Obs> store = new ConcurrentHashMap<>();
        final ObjectProvider<DeviceSpis> spis;
        final JdbcTemplate jdbc;

        StubEngine(ObjectProvider<DeviceSpis> spis, JdbcTemplate jdbc) {
            this.spis = spis;
            this.jdbc = jdbc;
        }

        /** A device message; returns the observation id (UNMATCHED when no session listens). */
        UUID reading(String instanceId, Category category, Map<String, Object> values, List<DeviceObservation.QualityFlag> flags) {
            Optional<DeviceCaptureSessions.SessionBinding> b = spis.getObject().sessions().activeSession(instanceId, Instant.now());
            UUID id = UUID.randomUUID();
            boolean restricted = category == Category.BREATH_ALCOHOL || category == Category.DRUG_SCREEN;
            DeviceObservation o = observation(id, category, b.map(DeviceCaptureSessions.SessionBinding::employeeId).orElse(null),
                    instanceId, DeviceCodes.CaptureMethod.DEVICE, values, flags, restricted,
                    b.isPresent() ? "SESSION" : null, INSTANCES.get(instanceId).model());
            if (b.isEmpty()) {
                store.put(id, new Obs(o, null, null, "UNMATCHED", true));
                notifyListeners(o, null, "UNMATCHED");
                return id;
            }
            boolean auto = "KIOSK".equals(b.get().mode());
            String st = o.blocked() ? "BLOCKED" : auto ? "ROUTED" : "PENDING_VERIFICATION";
            store.put(id, new Obs(o, b.get().formCode(), b.get().sessionId(), st, !auto));
            notifyListeners(o, b.get().sessionId(), st);
            return id;
        }

        static DeviceObservation observation(UUID id, Category category, String employeeId, String instanceId, DeviceCodes.CaptureMethod method,
                                             Map<String, Object> values, List<DeviceObservation.QualityFlag> flags, boolean restricted,
                                             String association, String model) {
            List<DeviceObservation.Component> comps = new ArrayList<>();
            values.forEach((k, v) -> comps.add(v instanceof Number n
                    ? new DeviceObservation.Component(k, null, "LOCAL:" + k, k, "QUANTITY", new BigDecimal(n.toString()), null, "1", null, null, null)
                    : new DeviceObservation.Component(k, null, "LOCAL:" + k, k, "STRING", null, String.valueOf(v), null, null, null, null)));
            DeviceObservation.Provenance p = new DeviceObservation.Provenance("MD-027", "Synthetic Medical", model, null, "SN-" + instanceId, "1.0",
                    null, "vitals.synthetic.pcd01", "1.0.0", "CAL-1", LocalDate.now(), "VALID", "nurse1", null, null, association,
                    association == null ? null : BigDecimal.ONE, 0, "9f86d0");
            return new DeviceObservation(id, null, category, employeeId, null, null, Instant.now(), instanceId, null, method, comps, flags, p,
                    List.of(new DeviceObservation.Attachment(UUID.randomUUID(), "PDF", "application/pdf", "abc", 10)), restricted);
        }

        /** Like the engine: listeners are called after the commit of the transaction that produced the notice. */
        void notifyListeners(DeviceObservation o, UUID sessionId, String status) {
            Runnable r = () -> {
                for (DeviceObservationListener l : spis.getObject().observationListeners()) {
                    l.observationUpdated(new DeviceObservationListener.ObservationNotice(o.observationId(), sessionId, o.deviceInstanceId(),
                            o.employeeId(), o.category(), status, o.qualityFlags(), Instant.now()));
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        r.run();
                    }
                });
            } else {
                r.run();
            }
        }

        String status(UUID id) {
            return store.get(id).status();
        }

        @Override
        public Optional<ObservationPreview> preview(UUID id) {
            Obs o = store.get(id);
            if (o == null) {
                return Optional.empty();
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            o.observation().components().forEach(c -> fields.put("measurements." + c.key(), c.value() != null ? c.value() : c.text()));
            return Optional.of(new ObservationPreview(o.observation(), o.status(), o.formCode(), "1", fields, o.verify()));
        }

        @Override
        public DeviceObservationSink.Delivery accept(String actor, UUID id, UUID sessionId, String note) {
            Obs o = store.get(id);
            if (o == null || !"PENDING_VERIFICATION".equals(o.status())) {
                throw new ApiException(o != null && "BLOCKED".equals(o.status()) ? HttpStatus.UNPROCESSABLE_CONTENT : HttpStatus.CONFLICT,
                        o != null && "BLOCKED".equals(o.status()) ? "observation_blocked" : "observation_not_pending", "not pending");
            }
            DeviceObservationSink.Delivery d;
            if (o.formCode().startsWith("E10.")) {
                d = DeviceObservationSink.Delivery.delivered("DA_TEST_READING", "DA-READING-" + id.toString().substring(0, 8));
            } else {
                d = route(actor, o);
            }
            store.put(id, new Obs(o.observation(), o.formCode(), o.sessionId(), "ROUTED", o.verify()));
            return d;
        }

        /** E03.RESULT: the E03 sink when deployed; otherwise the provenance columns it writes (V21). */
        DeviceObservationSink.Delivery route(String actor, Obs o) {
            Optional<DeviceObservationSink> sink = spis.getObject().sink("E03.RESULT");
            if (sink.isPresent()) {
                return sink.get().deliver(actor, new DeviceObservationSink.Routed(o.observation(), o.formCode(), "1", Map.of(), true));
            }
            List<String> existing = jdbc.queryForList("select id::text from ohrecord.result where device_observation_id = ?", String.class,
                    o.observation().observationId());
            if (!existing.isEmpty()) {
                return DeviceObservationSink.Delivery.duplicate("RESULT", existing.get(0));
            }
            UUID rid = UUID.randomUUID();
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into ohrecord.result (id, employee_id, category, code_system, code, code_display, flag, status, effective_at, "
                            + "received_at, source, device_id, device_model, classification, recorded_by, device_observation_id, capture_method, "
                            + "driver_version, verified_by, verified_at) values (?, ?, ?, 'LOINC', '85353-1', 'Vital signs panel', 'NORMAL', 'FINAL', "
                            + "?, ?, 'DEVICE', ?, ?, 'CLINICAL', ?, ?, ?, 'vitals.synthetic.pcd01@1.0.0', ?, ?)",
                    rid, o.observation().employeeId(), o.observation().category().name(), now, now, o.observation().deviceInstanceId(),
                    o.observation().provenance().model(), actor, o.observation().observationId(), o.observation().captureMethod().name(), actor, now);
            return DeviceObservationSink.Delivery.delivered("RESULT", rid.toString());
        }

        @Override
        public void reject(String actor, UUID id, UUID sessionId, String reasonCode, String note) {
            Obs o = store.get(id);
            if (o != null) {
                store.put(id, new Obs(o.observation(), o.formCode(), o.sessionId(), "REJECTED", o.verify()));
            }
        }

        @Override
        public UUID submitFallback(String actor, FallbackSubmission s) {
            UUID id = UUID.randomUUID();
            List<DeviceObservation.QualityFlag> flags = s.method() == DeviceCodes.CaptureMethod.MANUAL_ATTESTED
                    ? List.of(new DeviceObservation.QualityFlag("ATTESTATION", GateOutcome.PASS, "attested", null),
                    new DeviceObservation.QualityFlag("VERIFY_BEFORE_POST", GateOutcome.INFO, "verification_required", "Always verified"))
                    : List.of(new DeviceObservation.QualityFlag("OCR_CONFIDENCE", GateOutcome.INFO, "ocr_verified", "Check every value"));
            DeviceObservation o = observation(id, s.category(), s.employeeId(), s.deviceInstanceId(), s.method(), s.values(), flags, false,
                    "ENTERED_IN_FORM", s.attestedModel());
            store.put(id, new Obs(o, s.formCode(), s.sessionId(), "PENDING_VERIFICATION", true));
            notifyListeners(o, s.sessionId(), "PENDING_VERIFICATION");
            return id;
        }
    }

    /** E34 stand-in: test-only tokens for a kiosk, a second kiosk and a gateway (thumbprint checked by the kernel filter). */
    static class StubAuthenticator implements DeviceEndpointAuthenticator {
        @Override
        public Result authenticate(Credentials c) {
            if ("EP-T-KIOSK-01".equals(c.endpointId()) && "test-kiosk-token".equals(c.token())) {
                return Result.success(new DeviceEndpointPrincipal("EP-T-KIOSK-01", DeviceCodes.EndpointKind.KIOSK, "LHR", "DEVICE_VLAN", "GROUP",
                        Set.of("DEV-T-KIOSK-BP"), c.certificateSha256()));
            }
            if ("EP-T-KIOSK-02".equals(c.endpointId()) && "test-kiosk2-token".equals(c.token())) {
                return Result.success(new DeviceEndpointPrincipal("EP-T-KIOSK-02", DeviceCodes.EndpointKind.KIOSK, "LHR", "DEVICE_VLAN", "GROUP",
                        Set.of(), c.certificateSha256()));
            }
            if ("EP-T-GW-01".equals(c.endpointId()) && "test-gw-token".equals(c.token())) {
                return Result.success(new DeviceEndpointPrincipal("EP-T-GW-01", DeviceCodes.EndpointKind.GATEWAY, "LHR", "GATEWAY_ZONE", "GROUP",
                        Set.of(), c.certificateSha256()));
            }
            return Result.failure("bad_token");
        }
    }

    @TestConfiguration
    static class StubDeviceModules {
        @Bean
        @Primary
        DeviceCatalogueLookup stubCatalogue() {
            return new StubCatalogue();
        }

        @Bean
        @Primary
        StubEngine stubEngine(ObjectProvider<DeviceSpis> spis, JdbcTemplate jdbc) {
            return new StubEngine(spis, jdbc);
        }

        @Bean
        @Primary
        DeviceEndpointAuthenticator stubAuthenticator() {
            return new StubAuthenticator();
        }
    }

    // ------------------------------------------------------------------ helpers

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    JsonNode call(MockHttpServletRequestBuilder req, JwtRequestPostProcessor who, Object payload, int expected) throws Exception {
        if (payload != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(payload));
        }
        MvcResult r = mvc.perform(req.with(who)).andExpect(status().is(expected)).andReturn();
        String s = r.getResponse().getContentAsString();
        return s.isBlank() ? null : json.readTree(s);
    }

    JsonNode open(JwtRequestPostProcessor who, String form, String employee, String device, String station, String orderRef, int expected)
            throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("formCode", form);
        m.put("formVersion", "1");
        m.put("employeeId", employee);
        m.put("deviceInstanceId", device);
        m.put("siteCode", "LHR");
        m.put("stationCode", station);
        if (orderRef != null) {
            m.put("orderRef", orderRef);
        }
        return call(post("/api/v1/device-capture/sessions"), who, m, expected);
    }

    static List<DeviceObservation.QualityFlag> pass() {
        return List.of(new DeviceObservation.QualityFlag("CALIBRATION", GateOutcome.PASS, "calibration_valid", null));
    }

    static List<DeviceObservation.QualityFlag> flag(String gate, GateOutcome outcome, String code) {
        return List.of(new DeviceObservation.QualityFlag(gate, outcome, code, "Plain-language message for " + code));
    }

    MockHttpServletRequestBuilder kiosk(MockHttpServletRequestBuilder b, String endpoint, String token) {
        return b.header("X-Device-Endpoint", endpoint).header("X-Client-Cert-Sha256", THUMB).header("Authorization", "DeviceToken " + token);
    }

    long audits(String action, String resourceId) {
        Long n = jdbc.queryForObject("select count(*) from platform.audit_event where action = ? and resource_id = ?", Long.class, action, resourceId);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void formRegistryAndStationAwareDeviceList() throws Exception {
        JsonNode forms = call(get("/api/v1/device-capture/forms"), NURSE, null, 200);
        assertThat(forms.size()).isEqualTo(16);
        JsonNode vitals = call(get("/api/v1/device-capture/forms/E03.DEVICE_RESULT.VITALS"), DEVICE_ADMIN, null, 200);
        assertThat(vitals.path("target").asString()).isEqualTo("E03.RESULT");
        assertThat(vitals.path("fieldGroups").get(0).path("fields").get(0).path("path").asString()).isEqualTo("measurements.systolic");
        JsonNode breath = call(get("/api/v1/device-capture/forms/E10.BREATH_ALCOHOL"), COLLECTOR, null, 200);
        assertThat(breath.path("verification").path("restricted").asBoolean()).isTrue();
        // console roles read the field catalogue only – no device list for a person's capture
        call(get("/api/v1/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-OH-ROOM-1"), DEVICE_ADMIN, null, 403);
        call(get("/api/v1/device-capture/forms"), ERIN, null, 403);
        mvc.perform(get("/api/v1/device-capture/forms")).andExpect(status().isUnauthorized());

        MvcResult r = mvc.perform(get("/api/v1/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-OH-ROOM-1")
                .with(NURSE)).andExpect(status().isOk()).andReturn();
        String text = r.getResponse().getContentAsString();
        JsonNode list = json.readTree(text);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        list.forEach(n -> byId.put(n.path("deviceInstanceId").asString(), n));
        assertThat(byId).containsKeys("DEV-T-VITALS-2", "DEV-T-VITALS-LOCK").doesNotContainKey("DEV-T-VITALS-QUAR");
        assertThat(byId.get("DEV-T-VITALS-2").path("displayName").asString()).isEqualTo("Vital signs monitor – Room 1");
        assertThat(byId.get("DEV-T-VITALS-LOCK").path("status").asString()).isEqualTo("LOCKED_OUT");
        assertThat(byId.get("DEV-T-VITALS-LOCK").path("lockReason").asString()).isEqualTo("qc_due");
        // no technical parameters (E33-HLR-01)
        assertThat(text).doesNotContain("parameters").doesNotContain("endpointId").doesNotContain("EP-T-");
    }

    @Test
    void captureStreamPreviewAcceptFilesE03ResultWithProvenance() throws Exception {
        JsonNode s = open(NURSE, "E03.DEVICE_RESULT.VITALS", "E1001", "DEV-T-VITALS-1", "LHR-OH-ROOM-1", null, 201);
        String id = s.path("sessionId").asString();
        assertThat(s.path("state").asString()).isEqualTo("LISTENING");
        assertThat(s.path("contextPush").asString()).isEqualTo("PUSH_WORKLIST");
        assertThat(s.path("deviceName").asString()).isEqualTo("Vital signs monitor – Room 1");
        // one session per device
        JsonNode busy = open(NURSE2, "E03.DEVICE_RESULT.VITALS", "E1002", "DEV-T-VITALS-1", "LHR-OH-ROOM-1", null, 409);
        assertThat(busy.path("code").asString()).isEqualTo("device_busy");
        assertThat(busy.path("heldBy").asString()).isEqualTo("nurse1");
        // worklist push request for the engine (in-process, PHI-free)
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'device.capture.session.opened.v1' and aggregate_id = ?",
                Long.class, id)).isEqualTo(1L);
        // the engine sees the session (association priority 2)
        assertThat(spis.sessions().activeSession("DEV-T-VITALS-1", Instant.now())).get()
                .extracting(DeviceCaptureSessions.SessionBinding::employeeId).isEqualTo("E1001");

        // live stream: initial state, then the preview event after the reading arrives
        MvcResult stream = mvc.perform(get("/api/v1/device-capture/sessions/" + id + "/events").with(NURSE))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:state").contains("LISTENING");
        UUID obs = engine.reading("DEV-T-VITALS-1", Category.VITALS, Map.of("systolic", 128, "diastolic", 82, "pulse", 71), pass());
        assertThat(stream.getResponse().getContentAsString()).contains("event:preview").contains(obs.toString()).doesNotContain("128");

        JsonNode v = call(get("/api/v1/device-capture/sessions/" + id), NURSE, null, 200);
        assertThat(v.path("state").asString()).isEqualTo("PREVIEW");
        JsonNode p = v.path("preview");
        assertThat(p.path("values").size()).isEqualTo(3);
        assertThat(p.path("acceptEnabled").asBoolean()).isTrue();
        assertThat(p.path("marking").asString()).startsWith("device-sourced · Connex Spot");
        assertThat(p.path("provenance").path("associationMethod").asString()).isEqualTo("SESSION");
        assertThat(p.path("attachments").get(0).path("link").asString()).startsWith("/device-engine/observations/" + obs);
        assertThat(p.path("comparison").path("thresholds").size()).isGreaterThan(0);
        assertThat(audits("DEVICE_OBSERVATION_READ", id)).isPositive();
        // managers never see captures
        call(get("/api/v1/device-capture/sessions/" + id), MANAGER, null, 403);

        JsonNode acc = call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of("acknowledgedFlags", List.of()), 200);
        assertThat(acc.path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(acc.path("routedRecordType").asString()).isEqualTo("RESULT");
        Map<String, Object> result = jdbc.queryForMap("select employee_id, capture_method, verified_by, source from ohrecord.result where device_observation_id = ?", obs);
        assertThat(result).containsEntry("employee_id", "E1001").containsEntry("capture_method", "DEVICE").containsEntry("verified_by", "nurse1")
                .containsEntry("source", "DEVICE");
        assertThat(stream.getResponse().getContentAsString()).contains("ACCEPTED");
        // accepted values kept encrypted for comparison / trend
        String stored = jdbc.queryForObject("select values_json from devicecap.capture_observation where observation_id = ?", String.class, obs);
        assertThat(stored).startsWith("v1:").doesNotContain("128");
        // transitions logged and audited
        assertThat(jdbc.queryForList("select to_state from devicecap.capture_event where session_id = ?::uuid order by occurred_at", String.class, id))
                .containsSubsequence("LISTENING", "PREVIEW", "ACCEPTED");
        assertThat(audits("DEVICE_CAPTURE_OPEN", id)).isEqualTo(1);
        assertThat(audits("DEVICE_CAPTURE_ACCEPT", id)).isEqualTo(1);
        // the device is free again; accepting twice is refused
        assertThat(spis.sessions().activeSession("DEV-T-VITALS-1", Instant.now())).isEmpty();
        call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 409);

        // employee summary released in the preferred language (vitals: release rule on)
        JsonNode mine = call(get("/api/v1/me/device-summaries"), ERIN, null, 200);
        boolean found = false;
        for (JsonNode n : mine) {
            found |= n.path("plainLanguage").asString().contains("128/82 mmHg");
        }
        assertThat(found).isTrue();
        assertThat(jdbc.queryForObject("select plain_language from devicecap.employee_summary where observation_id = ?", String.class, obs))
                .startsWith("v1:");
        call(get("/api/v1/me/device-summaries"), NURSE, null, 403);
        // the comparison summary now includes this capture
        JsonNode cmp = call(get("/api/v1/device-capture/employees/E1001/summary?category=VITALS"), NURSE, null, 200);
        assertThat(cmp.path("trend").size()).isGreaterThanOrEqualTo(3);
        call(get("/api/v1/device-capture/employees/E1001/summary?category=VITALS"), MANAGER, null, 403);
    }

    @Test
    void warningsBlocksRetestAndReject() throws Exception {
        JsonNode s = open(NURSE, "E03.DEVICE_RESULT.SPIROMETRY", "E1006", "DEV-T-SPIRO-1", "LHR-OH-ROOM-2", null, 201);
        String id = s.path("sessionId").asString();
        engine.reading("DEV-T-SPIRO-1", Category.SPIROMETRY, Map.of("fev1L", 3.1, "fvcL", 4.2), flag("ATS_ERS_GRADE", GateOutcome.WARN, "grade_below_minimum"));
        JsonNode e = call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 422);
        assertThat(e.path("code").asString()).isEqualTo("flags_not_acknowledged");
        // re-test: the same session listens again
        JsonNode r = call(post("/api/v1/device-capture/sessions/" + id + "/reject"), NURSE, Map.of("reasonCode", "RETEST"), 200);
        assertThat(r.path("state").asString()).isEqualTo("LISTENING");
        assertThat(r.path("retestCount").asInt()).isEqualTo(1);
        // blocked by a gate: accept disabled, fall-back offered
        UUID blocked = engine.reading("DEV-T-SPIRO-1", Category.SPIROMETRY, Map.of("fev1L", 3.2, "fvcL", 4.3),
                flag("CALIBRATION", GateOutcome.BLOCK, "device_out_of_calibration"));
        JsonNode v = call(get("/api/v1/device-capture/sessions/" + id), NURSE, null, 200);
        assertThat(v.path("state").asString()).isEqualTo("PREVIEW");
        assertThat(v.path("preview").path("acceptEnabled").asBoolean()).isFalse();
        assertThat(v.path("preview").path("flags").get(0).path("message").asString()).contains("device_out_of_calibration");
        List<String> actions = new ArrayList<>();
        v.path("actions").forEach(a -> actions.add(a.asString()));
        assertThat(actions).doesNotContain("ACCEPT").contains("REJECT");
        assertThat(v.path("fallbackOptions").size()).isGreaterThan(0);
        assertThat(call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE,
                Map.of("acknowledgedFlags", List.of("device_out_of_calibration")), 422).path("code").asString()).isEqualTo("observation_blocked");
        JsonNode rej = call(post("/api/v1/device-capture/sessions/" + id + "/reject"), NURSE, Map.of("reasonCode", "DEVICE_FAULT"), 200);
        assertThat(rej.path("state").asString()).isEqualTo("REJECTED");
        assertThat(engine.status(blocked)).isEqualTo("REJECTED");
        // values of a rejected reading are never filed
        assertThat(jdbc.queryForObject("select count(*) from ohrecord.result where device_observation_id = ?", Long.class, blocked)).isZero();
        call(post("/api/v1/device-capture/sessions/" + id + "/reject"), NURSE, Map.of("reasonCode", "BROKEN"), 400);
    }

    @Test
    void fallbackChainAlternateDeviceOcrAndManualRecordThePath() throws Exception {
        // device locked out (daily check due) → the session opens with the fall-back chain on offer
        JsonNode s = open(NURSE, "E03.DEVICE_RESULT.VITALS", "E1005", "DEV-T-VITALS-LOCK", "LHR-OH-ROOM-1", null, 201);
        String id = s.path("sessionId").asString();
        assertThat(s.path("state").asString()).isEqualTo("FALLBACK_OFFERED");
        assertThat(s.path("fallbackReason").asString()).isEqualTo("DEVICE_LOCKED");
        List<String> paths = new ArrayList<>();
        s.path("fallbackOptions").forEach(o -> paths.add(o.path("path").asString()));
        assertThat(paths).containsSubsequence("ALTERNATE_DEVICE", "OCR_VERIFIED", "MANUAL_ATTESTED");
        // only the operator chooses
        call(post("/api/v1/device-capture/sessions/" + id + "/fallback"), NURSE2, Map.of("path", "MANUAL_ATTESTED", "reasonCode", "DEVICE_LOCKED"), 403);

        // (2) photo / OCR with mandatory verification
        MvcResult up = mvc.perform(multipart("/api/v1/device-capture/ocr").file(new MockMultipartFile("image", "display.png", "image/png", PNG))
                .param("deviceInstanceId", "DEV-T-VITALS-LOCK").with(NURSE)).andExpect(status().isCreated()).andReturn();
        String image = body(up).path("imageArchiveId").asString();
        assertThat(jdbc.queryForObject("select content from devicecap.ocr_image where id = ?::uuid", String.class, image)).startsWith("v1:");
        mvc.perform(multipart("/api/v1/device-capture/ocr").file(new MockMultipartFile("image", "x.gif", "image/gif", PNG))
                .param("category", "VITALS").with(NURSE)).andExpect(status().isUnsupportedMediaType());
        JsonNode bad = call(post("/api/v1/device-capture/sessions/" + id + "/fallback"), NURSE,
                Map.of("path", "OCR_VERIFIED", "reasonCode", "DEVICE_LOCKED", "imageArchiveId", image, "values", Map.of("colour", 1)), 422);
        assertThat(bad.path("code").asString()).isEqualTo("binding_field_unknown");
        JsonNode ocr = call(post("/api/v1/device-capture/sessions/" + id + "/fallback"), NURSE,
                Map.of("path", "OCR_VERIFIED", "reasonCode", "DEVICE_LOCKED", "imageArchiveId", image, "ocrConfidence", 0.95,
                        "values", Map.of("systolic", 131, "diastolic", 84)), 200);
        assertThat(ocr.path("state").asString()).isEqualTo("PREVIEW");
        assertThat(ocr.path("path").asString()).isEqualTo("OCR_VERIFIED");
        assertThat(ocr.path("preview").path("marking").asString()).isEqualTo("photo verified");
        UUID ocrObs = UUID.fromString(ocr.path("observationId").asString());
        call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 200);
        assertThat(jdbc.queryForObject("select capture_method from ohrecord.result where device_observation_id = ?", String.class, ocrObs))
                .isEqualTo("OCR_VERIFIED");
        assertThat(audits("DEVICE_CAPTURE_FALLBACK", id)).isPositive();

        // (3) manual entry with attestation
        String m = open(NURSE, "E03.DEVICE_RESULT.VITALS", "E1005", "DEV-T-VITALS-LOCK", "LHR-OH-ROOM-1", null, 201).path("sessionId").asString();
        Map<String, Object> attested = new LinkedHashMap<>(Map.of("model", "Connex Spot", "serial", "SN-OLD-1", "calibrationId", "CAL-77",
                "calibrationDate", LocalDate.now().plusDays(3).toString()));
        JsonNode inc = call(post("/api/v1/device-capture/sessions/" + m + "/fallback"), NURSE,
                Map.of("path", "MANUAL_ATTESTED", "reasonCode", "DEVICE_UNAVAILABLE", "values", Map.of("systolic", 122), "attested", attested), 422);
        assertThat(inc.path("code").asString()).isEqualTo("attestation_incomplete");
        attested.put("calibrationDate", LocalDate.now().minusDays(20).toString());
        JsonNode man = call(post("/api/v1/device-capture/sessions/" + m + "/fallback"), NURSE,
                Map.of("path", "MANUAL_ATTESTED", "reasonCode", "DEVICE_UNAVAILABLE", "values", Map.of("systolic", 122, "diastolic", 79),
                        "attested", attested), 200);
        assertThat(man.path("preview").path("marking").asString()).isEqualTo("entered manually – attested");
        UUID manObs = UUID.fromString(man.path("observationId").asString());
        call(post("/api/v1/device-capture/sessions/" + m + "/accept"), NURSE, Map.of(), 200);
        assertThat(jdbc.queryForObject("select capture_method from ohrecord.result where device_observation_id = ?", String.class, manObs))
                .isEqualTo("MANUAL_ATTESTED");

        // (1) another certified device of the same category at the station
        String a = open(NURSE, "E03.DEVICE_RESULT.VITALS", "E1005", "DEV-T-VITALS-LOCK", "LHR-OH-ROOM-1", null, 201).path("sessionId").asString();
        JsonNode alt = call(post("/api/v1/device-capture/sessions/" + a + "/fallback"), NURSE,
                Map.of("path", "ALTERNATE_DEVICE", "reasonCode", "DEVICE_LOCKED", "deviceInstanceId", "DEV-T-VITALS-2"), 200);
        assertThat(alt.path("state").asString()).isEqualTo("LISTENING");
        assertThat(alt.path("deviceInstanceId").asString()).isEqualTo("DEV-T-VITALS-2");
        engine.reading("DEV-T-VITALS-2", Category.VITALS, Map.of("systolic", 124, "diastolic", 80), pass());
        JsonNode done = call(post("/api/v1/device-capture/sessions/" + a + "/accept"), NURSE, Map.of(), 200);
        assertThat(done.path("state").asString()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("select path from devicecap.capture_session where id = ?::uuid", String.class, a)).isEqualTo("ALTERNATE_DEVICE");
        assertThat(jdbc.queryForObject("select original_device_instance_id from devicecap.capture_session where id = ?::uuid", String.class, a))
                .isEqualTo("DEV-T-VITALS-LOCK");
    }

    @Test
    void restrictedBreathAlcoholVisibility() throws Exception {
        open(NURSE, "E10.BREATH_ALCOHOL", "E1004", "DEV-T-BREATH-1", "LHR-OH-ROOM-3", "DA-TEST-1", 403);
        assertThat(open(COLLECTOR, "E10.BREATH_ALCOHOL", "E1004", "DEV-T-BREATH-1", "LHR-OH-ROOM-3", null, 422).path("code").asString())
                .isEqualTo("order_required");
        JsonNode s = open(COLLECTOR, "E10.BREATH_ALCOHOL", "E1004", "DEV-T-BREATH-1", "LHR-OH-ROOM-3", "DA-TEST-1", 201);
        String id = s.path("sessionId").asString();
        assertThat(s.path("restricted").asBoolean()).isTrue();
        // restricted: no photo path, manual only for the collector
        List<String> paths = new ArrayList<>();
        s.path("fallbackOptions").forEach(o -> paths.add(o.path("path").asString()));
        assertThat(paths).doesNotContain("OCR_VERIFIED").contains("MANUAL_ATTESTED");
        engine.reading("DEV-T-BREATH-1", Category.BREATH_ALCOHOL, Map.of("brac", 0.0, "sequence", "SCREEN"), pass());

        JsonNode collectorView = call(get("/api/v1/device-capture/sessions/" + id), COLLECTOR, null, 200);
        assertThat(collectorView.path("restrictedReading").asBoolean()).isFalse();
        assertThat(collectorView.path("preview").path("values").size()).isEqualTo(2);
        JsonNode nurseView = call(get("/api/v1/device-capture/sessions/" + id), NURSE, null, 200);
        assertThat(nurseView.path("restrictedReading").asBoolean()).isTrue();
        assertThat(nurseView.has("preview")).isFalse();
        JsonNode mroView = call(get("/api/v1/device-capture/sessions/" + id), MRO, null, 200);
        assertThat(mroView.path("preview").path("values").size()).isEqualTo(2);
        call(get("/api/v1/device-capture/sessions/" + id), COLLECTOR2, null, 404);
        call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 403);
        call(post("/api/v1/device-capture/sessions/" + id + "/accept"), COLLECTOR2, Map.of(), 404);
        JsonNode acc = call(post("/api/v1/device-capture/sessions/" + id + "/accept"), COLLECTOR, Map.of(), 200);
        assertThat(acc.path("routedRecordType").asString()).isEqualTo("DA_TEST_READING");
        // restricted readings are never released to the employee
        assertThat(jdbc.queryForObject("select count(*) from devicecap.employee_summary where session_id = ?::uuid", Long.class, id)).isZero();
    }

    @Test
    void ecgInterpretationNeedsPhysicianSignOff() throws Exception {
        String id = open(NURSE, "E03.DEVICE_RESULT.ECG", "E1002", "DEV-T-ECG-1", "LHR-OH-ROOM-2", null, 201).path("sessionId").asString();
        engine.reading("DEV-T-ECG-1", Category.ECG, Map.of("heartRate", 64, "qrsMs", 92, "qtcMs", 410, "interpretation", "Sinus rhythm"), pass());
        JsonNode acc = call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 200);
        assertThat(acc.path("interpretationStatus").asString()).isEqualTo("UNVERIFIED");
        call(post("/api/v1/device-capture/sessions/" + id + "/sign-off"), NURSE, Map.of(), 403);
        JsonNode signed = call(post("/api/v1/device-capture/sessions/" + id + "/sign-off"), PHYSICIAN, Map.of(), 200);
        assertThat(signed.path("interpretationStatus").asString()).isEqualTo("SIGNED");
        assertThat(signed.path("signedBy").asString()).isEqualTo("physician1");
        assertThat(jdbc.queryForObject("select count(*) from platform.outbox_event where event_type = 'device.capture.interpretation-signed.v1' "
                + "and aggregate_id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(audits("DEVICE_CAPTURE_SIGN_OFF", id)).isEqualTo(1);
        call(post("/api/v1/device-capture/sessions/" + id + "/sign-off"), PHYSICIAN, Map.of(), 409);
        // ECG is not released to the employee (release rule off)
        assertThat(jdbc.queryForObject("select count(*) from devicecap.employee_summary where session_id = ?::uuid", Long.class, id)).isZero();
        // annotations are screened and encrypted
        JsonNode note = call(post("/api/v1/device-capture/sessions/" + id + "/annotations"), PHYSICIAN, Map.of("text", "Repeat at next periodic review"), 201);
        assertThat(jdbc.queryForObject("select text from devicecap.capture_annotation where id = ?::uuid", String.class, note.path("id").asString()))
                .startsWith("v1:");
    }

    @Test
    void batchModeReconcilesOfflineReadingsInQueueOrder() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("siteCode", "LHR");
        req.put("screeningEventId", "SCR-2026-0042");
        req.put("formCode", "E03.DEVICE_RESULT.VITALS");
        req.put("deviceInstanceIds", List.of("DEV-T-BLE-1", "DEV-T-SCALE-1"));
        req.put("employeeIds", List.of("E1001", "E1002", "E1003"));
        req.put("badgeIds", List.of("B-1005"));
        JsonNode b = call(post("/api/v1/device-capture/batches"), NURSE, req, 201);
        String batch = b.path("batchId").asString();
        assertThat(b.path("sessions").size()).isEqualTo(4);
        assertThat(b.path("sessions").get(3).path("employeeId").asString()).isEqualTo("E1005");
        assertThat(b.path("sessions").get(0).path("current").asBoolean()).isTrue();
        call(get("/api/v1/device-capture/batches/" + batch), NURSE2, null, 404);
        // paired devices are busy for single captures
        JsonNode list = call(get("/api/v1/device-capture/devices?formCode=E03.DEVICE_RESULT.VITALS&siteCode=LHR&stationCode=LHR-SCREEN-1"), NURSE, null, 200);
        list.forEach(n -> assertThat(n.path("status").asString()).isEqualTo("BUSY"));

        // offline readings replayed in sequence order bind to the queue in order, per device
        engine.reading("DEV-T-BLE-1", Category.VITALS, Map.of("systolic", 121, "diastolic", 78), pass());
        engine.reading("DEV-T-SCALE-1", Category.VITALS, Map.of("weightKg", 70.2), pass());
        engine.reading("DEV-T-BLE-1", Category.VITALS, Map.of("systolic", 135, "diastolic", 86), pass());
        engine.reading("DEV-T-BLE-1", Category.VITALS, Map.of("systolic", 150, "diastolic", 95), flag("PLAUSIBILITY", GateOutcome.WARN, "outlier_systolic"));
        JsonNode after = call(get("/api/v1/device-capture/batches/" + batch), NURSE, null, 200);
        assertThat(after.path("sessions").get(0).path("readings").asInt()).isEqualTo(2);
        assertThat(after.path("sessions").get(1).path("readings").asInt()).isEqualTo(1);
        assertThat(after.path("sessions").get(2).path("readings").asInt()).isEqualTo(1);
        assertThat(after.path("sessions").get(3).path("current").asBoolean()).isTrue();
        String third = after.path("sessions").get(2).path("sessionId").asString();
        // the last employee did not attend: one-tap advance skips the entry
        JsonNode next = call(post("/api/v1/device-capture/batches/" + batch + "/next"), NURSE, null, 200);
        assertThat(next.path("sessions").get(3).path("state").asString()).isEqualTo("CANCELLED");
        // accept-all takes only previews without warnings
        JsonNode all = call(post("/api/v1/device-capture/batches/" + batch + "/accept-all"), NURSE, Map.of(), 200);
        assertThat(all.path("accepted").size()).isEqualTo(2);
        assertThat(all.path("skipped").get(0).path("sessionId").asString()).isEqualTo(third);
        assertThat(all.path("skipped").get(0).path("reason").asString()).isEqualTo("flags_need_review");
        assertThat(all.path("status").asString()).isEqualTo("OPEN");
        call(post("/api/v1/device-capture/sessions/" + third + "/accept"), NURSE, Map.of("acknowledgedFlags", List.of("outlier_systolic")), 200);
        JsonNode closed = call(post("/api/v1/device-capture/batches/" + batch + "/accept-all"), NURSE, Map.of(), 200);
        assertThat(closed.path("status").asString()).isEqualTo("CLOSED");
        assertThat(audits("DEVICE_BATCH_CREATE", batch)).isEqualTo(1);
    }

    @Test
    void screeningDayOfTwoHundredEmployeesReconcilesWithoutLoss() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 200; i++) {
            String id = String.format("ET%04d", i);
            ids.add(id);
            jdbc.update("insert into masterdata.employee (employee_id, display_name, subsidiary_code, department_code, preferred_language, updated_at) "
                    + "values (?, ?, 'CAT', 'CATERING', 'en', now()) on conflict (employee_id) do nothing", id, "Screening Person " + i);
        }
        JsonNode b = call(post("/api/v1/device-capture/batches"), NURSE, Map.of("siteCode", "LHR", "formCode", "E03.DEVICE_RESULT.VITALS",
                "deviceInstanceIds", List.of("DEV-T-BLE-200"), "employeeIds", ids), 201);
        String batch = b.path("batchId").asString();
        List<UUID> readings = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            readings.add(engine.reading("DEV-T-BLE-200", Category.VITALS, Map.of("systolic", 110 + (i % 40), "diastolic", 70 + (i % 20)), pass()));
        }
        // every reading reached its own queue entry, in order
        List<Map<String, Object>> bound = jdbc.queryForList("select s.employee_id, o.observation_id from devicecap.capture_session s "
                + "join devicecap.capture_observation o on o.session_id = s.id where s.batch_id = ?::uuid order by s.queue_position", batch);
        assertThat(bound).hasSize(200);
        for (int i = 0; i < 200; i++) {
            assertThat(bound.get(i).get("employee_id")).isEqualTo(ids.get(i));
            assertThat(bound.get(i).get("observation_id")).isEqualTo(readings.get(i));
        }
        JsonNode all = call(post("/api/v1/device-capture/batches/" + batch + "/accept-all"), NURSE, Map.of(), 200);
        assertThat(all.path("accepted").size()).isEqualTo(200);
        assertThat(all.path("status").asString()).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from ohrecord.result where device_observation_id in (select o.observation_id from "
                + "devicecap.capture_observation o join devicecap.capture_session s on s.id = o.session_id where s.batch_id = ?::uuid)", Long.class, batch))
                .isEqualTo(200L);
    }

    @Test
    void kioskSelfMeasurementWithBadgeAndConsent() throws Exception {
        // person tokens are not accepted on the machine path; the mTLS thumbprint is mandatory
        mvc.perform(post("/api/v1/device-ingest/kiosk/identify").with(NURSE).contentType(MediaType.APPLICATION_JSON).content("{\"badgeId\":\"B-1003\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/device-ingest/kiosk/identify").header("X-Device-Endpoint", "EP-T-KIOSK-01")
                        .header("Authorization", "DeviceToken test-kiosk-token").contentType(MediaType.APPLICATION_JSON).content("{\"badgeId\":\"B-1003\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("mtls_required"));
        // only kiosk endpoints
        mvc.perform(kiosk(post("/api/v1/device-ingest/kiosk/identify"), "EP-T-GW-01", "test-gw-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"badgeId\":\"B-1003\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("endpoint_forbidden"));
        mvc.perform(kiosk(post("/api/v1/device-ingest/kiosk/identify"), "EP-T-KIOSK-01", "test-kiosk-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"badgeId\":\"B-UNKNOWN\"}")).andExpect(status().isNotFound());

        MvcResult r = mvc.perform(kiosk(post("/api/v1/device-ingest/kiosk/identify"), "EP-T-KIOSK-01", "test-kiosk-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"badgeId\":\"B-1003\"}")).andExpect(status().isCreated()).andReturn();
        String text = r.getResponse().getContentAsString();
        JsonNode id = json.readTree(text);
        String ks = id.path("kioskSessionId").asString();
        assertThat(id.path("firstName").asString()).isEqualTo("Cara");
        assertThat(id.path("language").asString()).isEqualTo("fr");
        assertThat(id.path("consentRequired").asBoolean()).isTrue();
        // no other personal data leaves the core
        assertThat(text).doesNotContain("E1003").doesNotContain("crew1@").doesNotContain("Crew");

        // a reading before consent is not bound to the person
        UUID before = engine.reading("DEV-T-KIOSK-BP", Category.VITALS, Map.of("systolic", 119, "diastolic", 77), pass());
        assertThat(engine.status(before)).isEqualTo("UNMATCHED");
        mvc.perform(kiosk(post("/api/v1/device-ingest/kiosk/" + ks + "/consent"), "EP-T-KIOSK-01", "test-kiosk-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true,\"noticeVersion\":\"2020.1\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("notice_version_mismatch"));
        mvc.perform(kiosk(post("/api/v1/device-ingest/kiosk/" + ks + "/consent"), "EP-T-KIOSK-01", "test-kiosk-token")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"accepted\":true,\"noticeVersion\":\"" + id.path("consentNoticeVersion").asString() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.consented").value(true));
        assertThat(jdbc.queryForObject("select count(*) from platform.consent where employee_id = 'E1003' and purpose = 'KIOSK_SELF_MEASUREMENT' "
                + "and status = 'GRANTED'", Long.class)).isPositive();
        // another kiosk cannot see the session
        mvc.perform(kiosk(get("/api/v1/device-ingest/kiosk/" + ks + "/summary"), "EP-T-KIOSK-02", "test-kiosk2-token"))
                .andExpect(status().isNotFound());

        // the kiosk reading is auto-posted (screening form) and summarised in the employee's language
        UUID reading = engine.reading("DEV-T-KIOSK-BP", Category.VITALS, Map.of("systolic", 136, "diastolic", 84), pass());
        assertThat(engine.status(reading)).isEqualTo("ROUTED");
        MvcResult sum = mvc.perform(kiosk(get("/api/v1/device-ingest/kiosk/" + ks + "/summary"), "EP-T-KIOSK-01", "test-kiosk-token"))
                .andExpect(status().isOk()).andReturn();
        JsonNode summary = body(sum);
        assertThat(summary.path("language").asString()).isEqualTo("fr");
        assertThat(summary.path("items").get(0).path("plainLanguage").asString()).contains("Votre tension artérielle").contains("136/84");
        // and released to the employee app
        boolean found = false;
        for (JsonNode n : call(get("/api/v1/me/device-summaries"), CARA, null, 200)) {
            found |= n.path("plainLanguage").asString().contains("136/84");
        }
        assertThat(found).isTrue();
        assertThat(audits("DEVICE_KIOSK_SESSION", ks)).isPositive();
        // idle for more than 3 minutes: the privacy screen ends the session
        jdbc.update("update devicecap.capture_session set expires_at = now() - interval '1 minute' where id = ?::uuid", ks);
        mvc.perform(kiosk(get("/api/v1/device-ingest/kiosk/" + ks + "/summary"), "EP-T-KIOSK-01", "test-kiosk-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void employeeWearableConsentGrantAndRevoke() throws Exception {
        call(post("/api/v1/me/device-consents"), ERIN, Map.of("purpose", "WEARABLE_DATA", "scopes", List.of("GPS")), 400);
        JsonNode c = call(post("/api/v1/me/device-consents"), ERIN, Map.of("purpose", "WEARABLE_DATA", "scopes", List.of("STEPS", "SLEEP")), 201);
        assertThat(c.path("status").asString()).isEqualTo("ACTIVE");
        String consentId = c.path("consentId").asString();
        assertThat(jdbc.queryForObject("select status from platform.consent where id = ?::uuid", String.class, consentId)).isEqualTo("GRANTED");
        assertThat(call(get("/api/v1/me/device-consents"), ERIN, null, 200).size()).isPositive();
        call(delete("/api/v1/me/device-consents/" + c.path("id").asString()), PAUL, null, 404);
        JsonNode rev = call(delete("/api/v1/me/device-consents/" + c.path("id").asString()), ERIN, null, 200);
        assertThat(rev.path("status").asString()).isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject("select status from platform.consent where id = ?::uuid", String.class, consentId)).isEqualTo("WITHDRAWN");
        call(get("/api/v1/me/device-consents"), NURSE, null, 403);
    }

    @Test
    void sessionsExpireAndKpisCountPaths() throws Exception {
        String id = open(NURSE, "E03.DEVICE_RESULT.VITALS", "E1002", "DEV-T-EXPIRY-1", "LHR-OH-ROOM-4", null, 201).path("sessionId").asString();
        jdbc.update("update devicecap.capture_session set expires_at = now() - interval '1 minute' where id = ?::uuid", id);
        assertThat(spis.sessions().activeSession("DEV-T-EXPIRY-1", Instant.now())).isEmpty();
        assertThat(sessionService.expireDue()).isPositive();
        assertThat(jdbc.queryForObject("select state from devicecap.capture_session where id = ?::uuid", String.class, id)).isEqualTo("EXPIRED");
        assertThat(call(post("/api/v1/device-capture/sessions/" + id + "/accept"), NURSE, Map.of(), 409).path("code").asString()).isEqualTo("session_expired");
        assertThat(audits("DEVICE_CAPTURE_EXPIRE", id)).isEqualTo(1);
        // a reading arriving now is unmatched (engine KPI)
        assertThat(engine.status(engine.reading("DEV-T-EXPIRY-1", Category.VITALS, Map.of("systolic", 120), pass()))).isEqualTo("UNMATCHED");

        MvcResult r = mvc.perform(get("/api/v1/device-capture/kpis").with(NURSING_HEAD)).andExpect(status().isOk()).andReturn();
        JsonNode k = body(r).path("kpis");
        assertThat(k.path("sessions").asInt()).isPositive();
        assertThat(k.path("unmatched").asInt()).isPositive();
        assertThat(body(r).path("targets").path("usagePct").asInt()).isEqualTo(90);
        // the console sees counts only
        MvcResult admin = mvc.perform(get("/api/v1/device-capture/kpis?siteCode=LHR").with(DEVICE_ADMIN)).andExpect(status().isOk()).andReturn();
        assertThat(admin.getResponse().getContentAsString()).doesNotContain("E100").doesNotContain("employee");
        call(get("/api/v1/device-capture/kpis"), NURSE, null, 403);
        call(get("/api/v1/device-capture/kpis?from=2026-01-10&to=2026-01-01"), NURSING_HEAD, null, 400);
    }

    @Test
    void disabledModuleIsRefusedAndListenerSkips() throws Exception {
        jdbc.update("update masterdata.config_entry set config_value = 'false' where config_key = 'modules.e33.enabled'");
        try {
            call(get("/api/v1/device-capture/forms"), NURSE, null, 503);
            assertThat(spis.sessions().activeSession("DEV-T-VITALS-2", Instant.now())).isEmpty();
            // the listener must not throw
            engine.notifyListeners(StubEngine.observation(UUID.randomUUID(), Category.VITALS, "E1001", "DEV-T-VITALS-2",
                    DeviceCodes.CaptureMethod.DEVICE, Map.of("systolic", 1), pass(), false, null, "x"), UUID.randomUUID(), "PENDING_VERIFICATION");
        } finally {
            jdbc.update("update masterdata.config_entry set config_value = 'true' where config_key = 'modules.e33.enabled'");
        }
        call(get("/api/v1/device-capture/forms"), NURSE, null, 200);
        // the hash-chained audit is intact after all of the above
        assertThat(call(get("/api/v1/audit/verify"), user("auditor1", null, null, "AUDITOR"), null, 200).path("intact").asBoolean()).isTrue();
    }
}
