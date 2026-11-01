package com.wellnessz.app;

import static com.wellnessz.app.PlatformIntegrationTest.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DevicePackageSignatures;
import com.wellnessz.casekernel.device.DeviceSecretStore;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.service.CaptureSessionListener;
import com.wellnessz.e31.service.GatewayService;
import com.wellnessz.e31.service.TenantService;
import com.wellnessz.e34.signing.PackageSignatureService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Increment 6, second build round – the software gaps of the traceability matrix against PostgreSQL:
 * <ul>
 * <li>P6 vendor-cloud polling with OAuth2 client credentials against a mock vendor cloud (JDK HttpServer): token, pages, high-water
 * mark, idempotent re-poll, consent-scoped wearables, egress allow-list, residency check, vendor rate limit and back-off, status
 * (E31-BL-008, E31-HLR-02);</li>
 * <li>outbound orders through the gateway commands: start-from-form decision, ORM^O01 for the capture session over MLLP with the
 * device ACK, worklist push with a rejected order, serial start bytes (E31-HLR-08, E31-BL-004, -016, -019);</li>
 * <li>historic retrieval: request → RETRIEVE_HISTORY → QRY^R02 → result batch → deterministic de-duplication (E31-HLR-08);</li>
 * <li>tenant scoping: tenants with catalogue scope and residency, tenant-scoped catalogue and configurations (E32) and per-tenant
 * signing keys (E34) – negative isolation tests (E31-HLR-12).</li>
 * </ul>
 * The catalogue, endpoint authentication, connector secrets and capture sessions are stood in for through the kernel SPIs; the
 * engine (E31), the console (E32) and the signatures (E34) are the real modules.
 */
class Increment6DeviceRound2IntegrationTest extends IntegrationTestBase {

    static final String THUMB = "cd".repeat(32);
    static final JwtRequestPostProcessor ADMIN = user("deviceadmin1", "S8901", "/AIRLINE", "DEVICE_ADMIN");
    static final JwtRequestPostProcessor TESTER = user("inttester1", "S8903", "/AIRLINE", "INTEGRATION_TESTER");
    static final JwtRequestPostProcessor NURSE = user("nurse1", "N1", null, "OH_CLINICIAN");
    static final JwtRequestPostProcessor PLATFORM = user("platformadmin1", "S8909", "/AIRLINE", "PLATFORM_ADMIN");
    static final Map<String, DeviceCaptureSessions.SessionBinding> SESSIONS = new ConcurrentHashMap<>();
    /** Signed manifests per package (signed once with the real E34 service, per-tenant keys). */
    static final Map<String, DeviceCatalogueLookup.DriverPackage> SIGNED = new ConcurrentHashMap<>();
    /** Package id → tenant whose key signs it (default GROUP). */
    static final Map<String, String> SIGNING_TENANT = Map.of("vitals.welchallyn.csm-pcd01", "PARTNER");

    static HttpServer cloud;
    static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    static final AtomicInteger PAGE_CALLS = new AtomicInteger();
    static volatile boolean rateLimited;

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired CaptureSessionListener listener;
    @Autowired GatewayService gateways;
    @Autowired TenantService tenants;
    @Autowired PackageSignatureService signatures;
    @Autowired BundledDrivers bundled;

    // ------------------------------------------------------------------ mock vendor cloud

    @BeforeAll
    static void startCloud() throws IOException {
        cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 10);
        cloud.createContext("/oauth/token", Increment6DeviceRound2IntegrationTest::token);
        cloud.createContext("/v1/daily-summaries", Increment6DeviceRound2IntegrationTest::summaries);
        cloud.start();
    }

    @AfterAll
    static void stopCloud() {
        cloud.stop(0);
    }

    static String cloudUrl() {
        return "http://127.0.0.1:" + cloud.getAddress().getPort();
    }

    static void token(HttpExchange ex) throws IOException {
        TOKEN_CALLS.incrementAndGet();
        String form = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String basic = "Basic " + Base64.getEncoder().encodeToString("wz-client:cloud-secret".getBytes(StandardCharsets.UTF_8));
        boolean ok = "POST".equals(ex.getRequestMethod()) && basic.equals(ex.getRequestHeaders().getFirst("Authorization"))
                && form.contains("grant_type=client_credentials") && form.contains("scope=summaries.read");
        respond(ex, ok ? 200 : 401, ok ? "{\"access_token\":\"vendor-token-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}" : "{\"error\":\"invalid_client\"}",
                null);
    }

    static String record(String id, String employee, String day, int steps) {
        return "{\"id\":\"" + id + "\",\"subject\":{\"employeeNumber\":\"" + employee + "\"},\"day\":{\"start\":\"" + day + "T00:00:00Z\",\"end\":\"" + day
                + "T23:59:59Z\"},\"summary\":{\"steps\":" + steps + ",\"sleepMinutes\":400}}";
    }

    static void summaries(HttpExchange ex) throws IOException {
        PAGE_CALLS.incrementAndGet();
        if (!"Bearer vendor-token-1".equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "{}", null);
            return;
        }
        if (rateLimited) {
            respond(ex, 429, "{}", "120");
            return;
        }
        String q = ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery();
        String body;
        if (!q.contains("since=")) {
            body = "{\"items\":[" + record("r2-1001-28", "E1001", "2026-10-28", 8100) + "," + record("r2-1002-28", "E1002", "2026-10-28", 5000)
                    + "],\"nextCursor\":\"c1\"}";
        } else if (q.contains("since=c1")) {
            body = "{\"items\":[" + record("r2-1001-29", "E1001", "2026-10-29", 9300) + "]}";
        } else {
            // the vendor's "since" is inclusive: the last record comes again
            body = "{\"items\":[" + record("r2-1001-29", "E1001", "2026-10-29", 9300) + "]}";
        }
        respond(ex, 200, body, null);
    }

    static void respond(HttpExchange ex, int status, String body, String retryAfter) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) {
            ex.getResponseHeaders().add("Retry-After", retryAfter);
        }
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ------------------------------------------------------------------ kernel SPI stand-ins

    @TestConfiguration
    static class StandIns {

        static DeviceCatalogueLookup.DeviceInstance instance(String id, String pkg, String version, DeviceCodes.Family family, String option, String endpoint,
                                                             String serial, Map<String, Object> params, DeviceCodes.Category category, String tenant) {
            return new DeviceCatalogueLookup.DeviceInstance(id, "MD-T", family, "Synthetic", pkg, serial, null, "1.0", null, DeviceCodes.Option.ofCode(option),
                    "PUBLISHED", "LHR", "LHR-OH-ROOM-2", endpoint, pkg, version, Set.of(category), params,
                    List.of("WORKLIST", "SESSION", "VENDOR_ID_MATCH"), true, List.of(), "Europe/London", tenant, 2);
        }

        static Map<String, Object> cloudParams(String base, String residency) {
            return Map.of("apiBaseUrl", base, "residencyRegion", residency,
                    "oauth2", Map.of("tokenUrl", base + "/oauth/token", "clientId", "wz-client", "clientSecretRef", "devsec:r2-cloud"),
                    "poll", Map.of("enabled", true, "intervalSeconds", 120, "rateLimitPerMinute", 30),
                    "consentMapping", Map.of("purpose", "WEARABLE_DATA"));
        }

        static Map<String, DeviceCatalogueLookup.DeviceInstance> instances() {
            String base = cloudUrl();
            Map<String, Object> msh = Map.of("msh", Map.of("sendingApplication", "MUSE", "sendingFacility", "LHR", "receivingApplication", "WELLNESSZ"));
            return Map.ofEntries(
                    Map.entry("DEV-R2-WCP", instance("DEV-R2-WCP", "wear.cloud.daily-poll", "1.0.0", DeviceCodes.Family.WEARABLE, "P6", null, null,
                            cloudParams(base, "UK"), DeviceCodes.Category.WEARABLE, "GROUP")),
                    Map.entry("DEV-R2-WCP-EU", instance("DEV-R2-WCP-EU", "wear.cloud.daily-poll", "1.0.0", DeviceCodes.Family.WEARABLE, "P6", null, null,
                            cloudParams(base, "EU"), DeviceCodes.Category.WEARABLE, "GROUP")),
                    Map.entry("DEV-R2-WCP-OUT", instance("DEV-R2-WCP-OUT", "wear.cloud.daily-poll", "1.0.0", DeviceCodes.Family.WEARABLE, "P6", null, null,
                            cloudParams("https://api.not-allowed.example", "UK"), DeviceCodes.Category.WEARABLE, "GROUP")),
                    Map.entry("DEV-R2-ECG", instance("DEV-R2-ECG", "ecg.ge.mac-hl7", "1.0.0", DeviceCodes.Family.CARDIOLOGY, "P4", "EP-R2-GW", "MAC5500-77120",
                            msh, DeviceCodes.Category.ECG, "GROUP")),
                    Map.entry("DEV-R2-BRE", instance("DEV-R2-BRE", "da.lifeloc.fc20-serial", "1.0.0", DeviceCodes.Family.DRUG_ALCOHOL, "P1", "EP-R2-AGT",
                            "FC20-1", Map.of(), DeviceCodes.Category.BREATH_ALCOHOL, "GROUP")),
                    Map.entry("DEV-R2-SPI", instance("DEV-R2-SPI", "spiro.ndd.easyone-connect-gdt", "1.2.0", DeviceCodes.Family.SPIROMETRY, "P2", "EP-R2-AGT",
                            "SP-R2", Map.of(), DeviceCodes.Category.SPIROMETRY, "GROUP")),
                    Map.entry("DEV-R2-VIT", instance("DEV-R2-VIT", "vitals.welchallyn.csm-pcd01", "1.0.0", DeviceCodes.Family.VITALS, "P4", "EP-R2-GW",
                            "CSM-R2", Map.of(), DeviceCodes.Category.VITALS, "GROUP")),
                    Map.entry("DEV-R2-PVIT", instance("DEV-R2-PVIT", "vitals.welchallyn.csm-pcd01", "1.0.0", DeviceCodes.Family.VITALS, "P4", "EP-R2-PGW",
                            "CSM-P2", Map.of(), DeviceCodes.Category.VITALS, "PARTNER")),
                    Map.entry("DEV-R2-PVIT2", instance("DEV-R2-PVIT2", "vitals.welchallyn.csm-pcd01", "1.0.0", DeviceCodes.Family.VITALS, "P4", "EP-R2-PGW",
                            "CSM-P3", Map.of(), DeviceCodes.Category.VITALS, "PARTNER")));
        }

        @Bean
        @Primary
        DeviceCatalogueLookup round2Catalogue() {
            return new DeviceCatalogueLookup() {
                @Override
                public Optional<DeviceInstance> instance(String id) {
                    return Optional.ofNullable(instances().get(id));
                }

                @Override
                public List<DeviceInstance> instancesForEndpoint(String endpointId, String serial) {
                    return instances().values().stream().filter(i -> endpointId.equals(i.endpointId())).toList();
                }

                @Override
                public List<DeviceInstance> instancesAtStation(String site, String station, DeviceCodes.Category category) {
                    return List.of();
                }

                @Override
                public List<DeviceInstance> publishedInstances(DeviceCodes.Option option) {
                    return instances().values().stream().filter(i -> i.option() == option).toList();
                }

                @Override
                public Optional<DriverPackage> driverPackage(String packageId, String version) {
                    return Optional.ofNullable(SIGNED.get(packageId + "@" + version));
                }

                @Override
                public Optional<FormBinding> binding(String instanceId, String formCode) {
                    return Optional.empty();
                }

                @Override
                public QcStatus qcStatus(String instanceId, LocalDate on, String lot) {
                    return new QcStatus("VALID", "TEST", null, null, lot, null);
                }
            };
        }

        @Bean
        @Primary
        DeviceEndpointAuthenticator round2Authenticator() {
            return c -> {
                if (c.token() == null || !c.token().equals("tok-" + c.endpointId())) {
                    return DeviceEndpointAuthenticator.Result.failure("bad_token");
                }
                String tenant = c.endpointId().equals("EP-R2-PGW") ? "PARTNER" : "GROUP";
                DeviceCodes.EndpointKind kind = c.endpointId().equals("EP-R2-AGT") ? DeviceCodes.EndpointKind.DEVICE_HOST_AGENT
                        : DeviceCodes.EndpointKind.GATEWAY;
                return DeviceEndpointAuthenticator.Result.success(new DeviceEndpointPrincipal(c.endpointId(), kind, "LHR", "GATEWAY_ZONE", tenant, Set.of(),
                        c.certificateSha256()));
            };
        }

        @Bean
        @Primary
        DeviceSecretStore round2Secrets() {
            return new DeviceSecretStore() {
                @Override
                public String store(String actor, String scope, String name, char[] value) {
                    return "devsec:" + name;
                }

                @Override
                public Optional<char[]> resolve(String reference, String purpose) {
                    return "devsec:r2-cloud".equals(reference) && "CLOUD_POLL".equals(purpose) ? Optional.of("cloud-secret".toCharArray()) : Optional.empty();
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
        DeviceCaptureSessions round2Sessions() {
            return (instanceId, at) -> Optional.ofNullable(SESSIONS.get(instanceId));
        }

        /** One booked ECG for the order push (in addition to the E03 / E14 sources). */
        @Bean
        DeviceWorklistSource round2Worklist() {
            return (site, day, category) -> category == DeviceCodes.Category.ECG && "LHR".equals(site)
                    ? List.of(new DeviceWorklistSource.WorklistEntry("ORD-R2-1", "E1001", "Erin Employee", "B-1001", LocalDate.of(1985, 4, 12), "F", category, site,
                            Instant.parse("2026-11-01T10:00:00Z"), null))
                    : List.of();
        }
    }

    // ------------------------------------------------------------------ setup and helpers

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        tenants.put("system-test", "PARTNER", 1000, 10, "GROUP", "EU");
        jdbc.update("update masterdata.config_entry set config_value = '127.0.0.1' where config_key = 'device.cloud.allowed-hosts'");
        for (DriverPackage p : bundled.all()) {
            SIGNED.computeIfAbsent(p.ref(), ref -> {
                String tenant = SIGNING_TENANT.getOrDefault(p.packageId(), "GROUP");
                DevicePackageSignatures.Signature s = signatures.sign("system-test", tenant, p.canonicalJson().getBytes(StandardCharsets.UTF_8));
                return new DeviceCatalogueLookup.DriverPackage(p.packageId(), p.version(), p.family(), p.canonicalJson(), p.sha256(), s.keyId(),
                        s.signatureBase64());
            });
        }
    }

    MockHttpServletRequestBuilder device(MockHttpServletRequestBuilder b, String endpoint) {
        return b.header("X-Device-Endpoint", endpoint).header("Authorization", "DeviceToken tok-" + endpoint).header("X-Client-Cert-Sha256", THUMB);
    }

    JsonNode body(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString());
    }

    List<JsonNode> commands(String endpoint) throws Exception {
        JsonNode hb = body(mvc.perform(device(post("/api/v1/device-ingest/heartbeat"), endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn());
        List<JsonNode> out = new ArrayList<>();
        hb.path("commands").forEach(out::add);
        return out;
    }

    JsonNode poll(String instance) throws Exception {
        return body(mvc.perform(post("/api/v1/device-engine/ops/cloud-connectors/" + instance + "/poll").with(ADMIN)).andExpect(status().isOk()).andReturn());
    }

    // ------------------------------------------------------------------ P6 vendor-cloud polling

    @Test
    void vendorCloudPollingWithOAuth2HighWaterMarkIdempotentIngestConsentAllowListAndResidency() throws Exception {
        jdbc.update("update deviceint.cloud_poll_state set high_water_mark = null, next_poll_at = null, consecutive_failures = 0, records_total = 0, "
                + "duplicates_total = 0, skipped_no_consent = 0 where device_instance_id like 'DEV-R2-WCP%'");
        TOKEN_CALLS.set(0);
        rateLimited = false;
        JsonNode first = poll("DEV-R2-WCP");
        assertThat(first.path("status").asString()).isEqualTo("OK");
        assertThat(first.path("records").asInt()).isEqualTo(3); // two pages
        assertThat(first.path("accepted").asInt()).isEqualTo(2);
        assertThat(first.path("skippedNoConsent").asInt()).isEqualTo(1); // E1002 has no WEARABLE_DATA consent: never stored
        assertThat(first.path("highWaterMark").asString()).isEqualTo("2026-10-29T23:59:59Z");
        assertThat(TOKEN_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where endpoint_id = 'CLOUD:DEV-R2-WCP'", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where endpoint_id = 'CLOUD:DEV-R2-WCP' and idempotency_key like 'CP:DEV-R2-WCP:r2-1002%'",
                Integer.class)).isZero();
        // second run from the high-water mark: the vendor repeats the last record – idempotent (DUPLICATE), the token is reused
        JsonNode second = poll("DEV-R2-WCP");
        assertThat(second.path("accepted").asInt()).isZero();
        assertThat(second.path("duplicates").asInt()).isEqualTo(1);
        assertThat(TOKEN_CALLS.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where endpoint_id = 'CLOUD:DEV-R2-WCP'", Integer.class)).isEqualTo(2);
        // vendor rate limit: the connector backs off for Retry-After and keeps its high-water mark
        rateLimited = true;
        try {
            JsonNode limited = poll("DEV-R2-WCP");
            assertThat(limited.path("status").asString()).isEqualTo("RATE_LIMITED");
            assertThat(Instant.parse(limited.path("nextPollAt").asString())).isAfter(Instant.now().plus(100, ChronoUnit.SECONDS));
            assertThat(jdbc.queryForObject("select high_water_mark from deviceint.cloud_poll_state where device_instance_id = 'DEV-R2-WCP'", String.class))
                    .isEqualTo("2026-10-29T23:59:59Z");
        } finally {
            rateLimited = false;
        }
        // residency of the connector must equal the tenant's; hosts must be on device.cloud.allowed-hosts – no outbound call otherwise
        int pages = PAGE_CALLS.get();
        assertThat(poll("DEV-R2-WCP-EU").path("status").asString()).isEqualTo("RESIDENCY_MISMATCH");
        JsonNode out = poll("DEV-R2-WCP-OUT");
        assertThat(out.path("status").asString()).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(out.path("error").asString()).isEqualTo("host_not_allowed");
        assertThat(PAGE_CALLS.get()).isEqualTo(pages);
        // status for the operations screen (PHI-free)
        MvcResult st = mvc.perform(get("/api/v1/device-engine/ops/cloud-connectors").with(TESTER)).andExpect(status().isOk()).andReturn();
        JsonNode wcp = null;
        for (JsonNode n : body(st)) {
            if ("DEV-R2-WCP".equals(n.path("deviceInstanceId").asString())) {
                wcp = n;
            }
        }
        assertThat(wcp).isNotNull();
        assertThat(wcp.path("state").asString()).isEqualTo("RATE_LIMITED");
        assertThat(wcp.path("recordsTotal").asInt()).isEqualTo(2);
        assertThat(wcp.path("skippedNoConsent").asInt()).isEqualTo(1);
        assertThat(wcp.path("consentPurpose").asString()).isEqualTo("WEARABLE_DATA");
        assertThat(wcp.path("hostAllowed").asBoolean()).isTrue();
        assertThat(st.getResponse().getContentAsString()).doesNotContain("E1001").doesNotContain("vendor-token");
        mvc.perform(post("/api/v1/device-engine/ops/cloud-connectors/DEV-R2-WCP/poll").with(NURSE)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from devicesec.security_event where type = 'CLOUD_POLL_REFUSED' and device_instance_id = 'DEV-R2-WCP-OUT'",
                Integer.class)).isPositive();
    }

    // ------------------------------------------------------------------ outbound orders, start from form

    @Test
    void startFromFormAndWorklistPushGoThroughTheGatewayCommandsWithAcknowledgements() throws Exception {
        commands("EP-R2-GW");
        commands("EP-R2-AGT");
        UUID session = UUID.randomUUID();
        SESSIONS.put("DEV-R2-ECG", new DeviceCaptureSessions.SessionBinding(session, "E1001", null, "ORD-R2-1", "E03.DEVICE_RESULT.ECG", "1", "nurse1", "LHR",
                "LHR-OH-ROOM-2", "FORM", Instant.now(), Instant.now().plusSeconds(600)));
        // the engine starts acquisition only where the driver declares a start command; otherwise the worklist push it supports
        assertThat(listener.handle(json.readTree("{\"contextPush\":\"START_ACQUISITION\",\"deviceInstanceId\":\"DEV-R2-ECG\",\"sessionId\":\"" + session + "\"}")))
                .isEqualTo("START_ACQUISITION");
        assertThat(listener.handle(json.readTree("{\"contextPush\":\"START_ACQUISITION\",\"deviceInstanceId\":\"DEV-R2-SPI\",\"sessionId\":\"" + UUID.randomUUID() + "\"}")))
                .isEqualTo("PUSH_WORKLIST"); // GDT 6302: the vendor software turns the request into the test
        assertThat(listener.handle(json.readTree("{\"contextPush\":\"PUSH_WORKLIST\",\"deviceInstanceId\":\"DEV-R2-BRE\",\"sessionId\":\"" + UUID.randomUUID() + "\"}")))
                .isNull(); // no worklist capability
        JsonNode start = commands("EP-R2-GW").stream().filter(c -> "START_ACQUISITION".equals(c.path("type").asString())).findFirst().orElseThrow();
        String commandId = start.path("commandId").asString();
        // another gateway cannot fetch the content
        mvc.perform(device(get("/api/v1/device-ingest/commands/" + commandId + "/outbound"), "EP-R2-AGT")).andExpect(status().isNotFound());
        JsonNode outbound = body(mvc.perform(device(get("/api/v1/device-ingest/commands/" + commandId + "/outbound"), "EP-R2-GW")).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store")).andReturn());
        assertThat(outbound.path("transport").asString()).isEqualTo("MLLP");
        JsonNode msg = outbound.path("messages").get(0);
        assertThat(msg.path("messageType").asString()).isEqualTo("ORM^O01");
        String er7 = msg.path("er7").asString();
        assertThat(er7).startsWith("MSH|^~\\&|WELLNESSZ||MUSE|LHR|").contains("ORM^O01").contains("|E1001^^^WELLNESSZ^EI").contains("ORC|NW|ORD-R2-1^WELLNESSZ")
                .contains("19850412"); // the GE MAC driver declares date of birth and sex (ECG interpretation)
        String control = msg.path("controlId").asString();
        mvc.perform(device(post("/api/v1/device-ingest/commands/" + commandId + "/result"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\",\"detail\":\"1/1 start messages acknowledged\",\"acks\":[{\"controlId\":\"" + control + "\",\"code\":\"AA\"}]}"))
                .andExpect(status().isNoContent());
        // the capture panel sees the outbound order and its acknowledgement – no message content
        MvcResult panel = mvc.perform(get("/api/v1/device-engine/outbound").param("sessionId", session.toString()).with(NURSE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].status").value("ACKED")).andExpect(jsonPath("$.messages[0].ackCode").value("AA"))
                .andExpect(jsonPath("$.messages[0].messageType").value("ORM^O01")).andExpect(jsonPath("$.messages[0].orderRef").value("ORD-R2-1")).andReturn();
        assertThat(panel.getResponse().getContentAsString()).doesNotContain("MSH|").doesNotContain("19850412");
        // worklist push of the day: the device rejects the order (AE) – the order is REJECTED, the command FAILED
        UUID push = gateways.queueCommand("EP-R2-GW", "PUSH_WORKLIST", "DEV-R2-ECG", Map.of("orderRef", "ORD-R2-1"), "test");
        JsonNode pushed = body(mvc.perform(device(get("/api/v1/device-ingest/commands/" + push + "/outbound"), "EP-R2-GW")).andExpect(status().isOk()).andReturn());
        assertThat(pushed.path("messages")).hasSize(1);
        String pushControl = pushed.path("messages").get(0).path("controlId").asString();
        mvc.perform(device(post("/api/v1/device-ingest/commands/" + push + "/result"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\",\"detail\":\"0/1 worklist messages acknowledged\",\"acks\":[{\"controlId\":\"" + pushControl
                        + "\",\"code\":\"AE\",\"text\":\"unknown order\"}]}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/device-engine/outbound").param("deviceInstanceId", "DEV-R2-ECG").with(ADMIN)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status || ':' || ack_code from deviceint.outbound_message where command_id = ? and control_id = ?", String.class,
                push, pushControl)).isEqualTo("REJECTED:AE");
        assertThat(jdbc.queryForObject("select status from deviceint.gateway_command where id = ?", String.class, push)).isEqualTo("FAILED");
        // serial device: the start bytes the driver declares
        SESSIONS.put("DEV-R2-BRE", new DeviceCaptureSessions.SessionBinding(UUID.randomUUID(), "E1001", null, null, "E10.BREATH_ALCOHOL", "1", "collector1",
                "LHR", "LHR-OH-ROOM-2", "FORM", Instant.now(), Instant.now().plusSeconds(600)));
        assertThat(listener.handle(json.readTree("{\"contextPush\":\"START_ACQUISITION\",\"deviceInstanceId\":\"DEV-R2-BRE\",\"sessionId\":\"" + UUID.randomUUID() + "\"}")))
                .isEqualTo("START_ACQUISITION");
        JsonNode serialStart = commands("EP-R2-AGT").stream().filter(c -> "START_ACQUISITION".equals(c.path("type").asString())).findFirst().orElseThrow();
        JsonNode serial = body(mvc.perform(device(get("/api/v1/device-ingest/commands/" + serialStart.path("commandId").asString() + "/outbound"), "EP-R2-AGT"))
                .andExpect(status().isOk()).andReturn());
        assertThat(serial.path("transport").asString()).isEqualTo("SERIAL");
        assertThat(new String(Base64.getDecoder().decode(serial.path("payloadBase64").asString()), StandardCharsets.ISO_8859_1)).isEqualTo("START\r\n");
        assertThat(serial.path("expectPattern").asString()).isEqualTo("^(READY|BLOW)");
    }

    // ------------------------------------------------------------------ historic retrieval

    @Test
    void historicRetrievalRunsThroughTheGatewayAndDeduplicatesDeterministically() throws Exception {
        commands("EP-R2-GW");
        Instant to = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant from = to.minus(10, ChronoUnit.DAYS);
        String req = "{\"deviceInstanceId\":\"DEV-R2-ECG\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}";
        mvc.perform(post("/api/v1/device-engine/historic-retrievals").with(NURSE).contentType(MediaType.APPLICATION_JSON).content(req)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/device-engine/historic-retrievals").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceInstanceId\":\"DEV-R2-SPI\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("historic_retrieval_not_supported"));
        mvc.perform(post("/api/v1/device-engine/historic-retrievals").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceInstanceId\":\"DEV-R2-ECG\",\"from\":\"" + to.minus(40, ChronoUnit.DAYS) + "\",\"to\":\"" + to + "\"}")).andExpect(status().isBadRequest());
        JsonNode created = body(mvc.perform(post("/api/v1/device-engine/historic-retrievals").with(TESTER).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andExpect(jsonPath("$.method").value("HL7_QUERY")).andReturn());
        String retrieval = created.path("id").asString();
        JsonNode command = commands("EP-R2-GW").stream().filter(c -> "RETRIEVE_HISTORY".equals(c.path("type").asString())).findFirst().orElseThrow();
        String commandId = command.path("commandId").asString();
        JsonNode outbound = body(mvc.perform(device(get("/api/v1/device-ingest/commands/" + commandId + "/outbound"), "EP-R2-GW")).andExpect(status().isOk()).andReturn());
        assertThat(outbound.path("retrievalId").asString()).isEqualTo(retrieval);
        String query = outbound.path("messages").get(0).path("er7").asString();
        assertThat(query).contains("QRY^R02").contains("QRD|").contains("|MAC5500-77120|RES|").contains("QRF|MAC5500-77120|");
        String normal = new String(getClass().getResourceAsStream("/e31/golden/cardiology/ecg.ge.mac-hl7/normal.hl7").readAllBytes(), StandardCharsets.ISO_8859_1)
                .replace("ECG0001", "HR-" + UUID.randomUUID().toString().substring(0, 8));
        String edge = new String(getClass().getResourceAsStream("/e31/golden/cardiology/ecg.ge.mac-hl7/edge-long-qtc.hl7").readAllBytes(),
                StandardCharsets.ISO_8859_1).replace("|P|2.5", "|P|2.5").replace("20261030", "20261029");
        String batch = batch(normal, edge, false);
        JsonNode r1 = body(mvc.perform(device(post("/api/v1/device-ingest/retrievals/" + retrieval + "/batch"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content(batch)).andExpect(status().isOk()).andReturn());
        assertThat(r1.path("retrieval").path("accepted").asInt()).isEqualTo(2);
        assertThat(r1.path("retrieval").path("status").asString()).isEqualTo("RECEIVING");
        int messages = jdbc.queryForObject("select count(*) from deviceint.message where device_instance_id = 'DEV-R2-ECG'", Integer.class);
        // the same results again with new transmission fields (MSH-7 / MSH-10): deterministic keys – duplicates, nothing stored twice
        String again = batch(normal.replace("20261030093000||ORU", "20261101120000||ORU").replaceFirst("\\|HR-[0-9a-f]{8}\\|", "|HR-RESENT|"), edge, true);
        JsonNode r2 = body(mvc.perform(device(post("/api/v1/device-ingest/retrievals/" + retrieval + "/batch"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content(again)).andExpect(status().isOk()).andReturn());
        assertThat(r2.path("retrieval").path("duplicates").asInt()).isEqualTo(2);
        assertThat(r2.path("retrieval").path("accepted").asInt()).isEqualTo(2);
        assertThat(r2.path("retrieval").path("status").asString()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where device_instance_id = 'DEV-R2-ECG'", Integer.class)).isEqualTo(messages);
        // a result the device had already pushed live (same bytes) is recognised too
        String live = new String(getClass().getResourceAsStream("/e31/golden/cardiology/ecg.ge.mac-hl7/normal.hl7").readAllBytes(), StandardCharsets.ISO_8859_1)
                .replace("ECG0001", "LIVE-" + UUID.randomUUID().toString().substring(0, 6));
        mvc.perform(device(post("/api/v1/device-ingest/hl7v2"), "EP-R2-GW").header("Idempotency-Key", "live-" + UUID.randomUUID()).header("X-Device-Instance", "DEV-R2-ECG")
                .contentType("application/hl7-v2").content(live)).andExpect(status().isOk());
        String second = body(mvc.perform(post("/api/v1/device-engine/historic-retrievals").with(ADMIN).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated()).andReturn()).path("id").asString();
        JsonNode r3 = body(mvc.perform(device(post("/api/v1/device-ingest/retrievals/" + second + "/batch"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content(batch(live, null, true))).andExpect(status().isOk()).andReturn());
        assertThat(r3.path("results").get(0).path("receipt").path("status").asString()).isEqualTo("DUPLICATE");
        // only the gateway of the device may deliver; the command result closes the retrieval
        mvc.perform(device(post("/api/v1/device-ingest/retrievals/" + retrieval + "/batch"), "EP-R2-AGT").contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isNotFound());
        mvc.perform(device(post("/api/v1/device-ingest/commands/" + commandId + "/result"), "EP-R2-GW").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\",\"detail\":\"2 results returned\"}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/device-engine/historic-retrievals/" + retrieval).with(TESTER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(jsonPath("$.received").value(4));
        mvc.perform(get("/api/v1/device-engine/historic-retrievals").param("deviceInstanceId", "DEV-R2-ECG").with(TESTER)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceInstanceId").value("DEV-R2-ECG"));
    }

    String batch(String a, String b, boolean last) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(Map.of("kind", "HL7V2", "idempotencyKey", "HQ:a", "sequence", 1, "contentType", "application/hl7-v2", "body", a));
        if (b != null) {
            items.add(Map.of("kind", "HL7V2", "idempotencyKey", "HQ:b", "sequence", 2, "contentType", "application/hl7-v2", "body", b));
        }
        return json.writeValueAsString(Map.of("items", items, "last", last));
    }

    // ------------------------------------------------------------------ tenants

    @Test
    void tenantScopedCatalogueConfigurationsAndSigningKeysAreIsolated() throws Exception {
        // tenants (E31 /device-engine/tenants): residency and catalogue scope
        mvc.perform(put("/api/v1/device-engine/tenants/PARTNER/quota").with(PLATFORM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messagesPerMinute\":1000,\"maxConnectors\":10,\"catalogueScope\":\"GROUP\",\"residencyRegion\":\"EU\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.residencyRegion").value("EU"));
        mvc.perform(put("/api/v1/device-engine/tenants/PARTNER/quota").with(PLATFORM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"messagesPerMinute\":1000,\"maxConnectors\":10,\"catalogueScope\":\"ELSEWHERE\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/device-engine/tenants").with(ADMIN)).andExpect(status().isForbidden());
        // per-tenant signing keys: the partner's key signs only partner packages
        byte[] manifest = SIGNED.get("vitals.welchallyn.csm-pcd01@1.0.0").manifestJson().getBytes(StandardCharsets.UTF_8);
        DeviceCatalogueLookup.DriverPackage partnerSigned = SIGNED.get("vitals.welchallyn.csm-pcd01@1.0.0");
        assertThat(signatures.verify(manifest, partnerSigned.signatureBase64(), partnerSigned.signatureKeyId(), "PARTNER").valid()).isTrue();
        assertThat(signatures.verify(manifest, partnerSigned.signatureBase64(), partnerSigned.signatureKeyId(), "GROUP").failureCode()).isEqualTo("key_not_for_tenant");
        assertThat(signatures.verify(manifest, partnerSigned.signatureBase64(), partnerSigned.signatureKeyId()).failureCode()).isEqualTo("key_not_for_tenant");
        DeviceCatalogueLookup.DriverPackage groupSigned = SIGNED.get("ecg.ge.mac-hl7@1.0.0");
        assertThat(signatures.verify(groupSigned.manifestJson().getBytes(StandardCharsets.UTF_8), groupSigned.signatureBase64(), groupSigned.signatureKeyId(),
                "PARTNER").valid()).isTrue(); // group keys sign the shared catalogue for every tenant
        assertThat(signatures.keys()).anyMatch(k -> "PARTNER".equals(k.tenantCode()) && "ACTIVE".equals(k.status()))
                .anyMatch(k -> "GROUP".equals(k.tenantCode()) && "ACTIVE".equals(k.status()));
        // the engine applies it at load: the partner's device runs the partner-signed package, a group device refuses it
        String hl7 = new String(getClass().getResourceAsStream("/e31/golden/vitals/csm-pcd01/normal.hl7").readAllBytes(), StandardCharsets.ISO_8859_1);
        String pk = "R2P:" + UUID.randomUUID();
        mvc.perform(device(post("/api/v1/device-ingest/hl7v2"), "EP-R2-PGW").header("Idempotency-Key", pk).header("X-Device-Instance", "DEV-R2-PVIT")
                .contentType("application/hl7-v2").content(hl7)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select status from deviceint.message where endpoint_id = 'EP-R2-PGW' and idempotency_key = ?", String.class, pk))
                .isEqualTo("PROCESSED");
        String gk = "R2G:" + UUID.randomUUID();
        mvc.perform(device(post("/api/v1/device-ingest/hl7v2"), "EP-R2-GW").header("Idempotency-Key", gk).header("X-Device-Instance", "DEV-R2-VIT")
                .contentType("application/hl7-v2").content(hl7)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select error_code from deviceint.message where endpoint_id = 'EP-R2-GW' and idempotency_key = ?", String.class, gk))
                .isEqualTo("key_not_for_tenant");
        // per-tenant connector limit: with one connector allowed, a second partner device is refused (429 tenant_connector_limit)
        tenants.put("system-test", "PARTNER", 1000, 1, "GROUP", "EU");
        String lk = "R2L:" + UUID.randomUUID();
        MvcResult limited = mvc.perform(device(post("/api/v1/device-ingest/fhir"), "EP-R2-PGW").header("Idempotency-Key", lk).header("X-Device-Instance", "DEV-R2-PVIT2")
                .contentType("application/fhir+json").content("{\"resourceType\":\"Observation\"}")).andReturn();
        assertThat(limited.getResponse().getStatus()).isEqualTo(429);
        assertThat(limited.getResponse().getContentAsString()).contains("tenant_connector_limit");
        assertThat(jdbc.queryForObject("select count(*) from deviceint.message where idempotency_key = ?", Integer.class, lk)).isZero();
        tenants.put("system-test", "PARTNER", 1000, 10, "GROUP", "EU");
        // the partner's endpoint cannot reach a group device and vice versa
        mvc.perform(device(post("/api/v1/device-ingest/hl7v2"), "EP-R2-PGW").header("Idempotency-Key", "R2X:" + UUID.randomUUID())
                .header("X-Device-Instance", "DEV-R2-VIT").contentType("application/hl7-v2").content(hl7)).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).contains("MSA|AR"));
        // tenant-scoped catalogue (E32): a partner model is invisible to the group and to other tenants
        try {
            mvc.perform(put("/api/v1/device-catalogue/models/MD-121/tenant").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"tenantCode\":\"PARTNER\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.tenantCode").value("PARTNER"));
            assertThat(modelCodes("GROUP")).doesNotContain("MD-121").contains("MD-045");
            assertThat(modelCodes("PARTNER")).contains("MD-121", "MD-045");
            tenants.put("system-test", "PARTNER", 1000, 10, "TENANT", "EU");
            assertThat(modelCodes("PARTNER")).containsExactly("MD-121");
            tenants.put("system-test", "OTHER", 100, 5, "GROUP", "UK");
            assertThat(modelCodes("OTHER")).doesNotContain("MD-121");
            // configurations: a group configuration cannot use the partner's model; the partner's configuration is listed for it only
            MvcResult refused = mvc.perform(post("/api/v1/device-configurations").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"modelCode\":\"MD-121\",\"siteCode\":\"LHR\"}")).andExpect(status().isUnprocessableContent()).andReturn();
            assertThat(refused.getResponse().getContentAsString()).contains("model_not_in_tenant_catalogue");
            mvc.perform(post("/api/v1/device-configurations").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"modelCode\":\"MD-121\",\"siteCode\":\"LHR\",\"tenantCode\":\"NOPE\"}")).andExpect(status().isBadRequest());
            JsonNode cfg = body(mvc.perform(post("/api/v1/device-configurations").with(ADMIN).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"modelCode\":\"MD-121\",\"siteCode\":\"LHR\",\"tenantCode\":\"PARTNER\"}")).andExpect(status().isCreated()).andReturn());
            String id = cfg.path("configurationId").asString();
            assertThat(configurationIds("PARTNER")).contains(id);
            assertThat(configurationIds("GROUP")).doesNotContain(id);
        } finally {
            tenants.put("system-test", "PARTNER", 1000, 10, "GROUP", "EU");
            jdbc.update("update devicecat.model set tenant_code = null where model_code = 'MD-121'");
        }
    }

    List<String> modelCodes(String tenant) throws Exception {
        List<String> out = new ArrayList<>();
        body(mvc.perform(get("/api/v1/device-catalogue/models").param("tenantCode", tenant).with(ADMIN)).andExpect(status().isOk()).andReturn())
                .forEach(n -> out.add(n.path("modelCode").asString()));
        return out;
    }

    List<String> configurationIds(String tenant) throws Exception {
        List<String> out = new ArrayList<>();
        body(mvc.perform(get("/api/v1/device-configurations").param("tenantCode", tenant).with(ADMIN)).andExpect(status().isOk()).andReturn())
                .forEach(n -> out.add(n.path("configurationId").asString()));
        return out;
    }
}
