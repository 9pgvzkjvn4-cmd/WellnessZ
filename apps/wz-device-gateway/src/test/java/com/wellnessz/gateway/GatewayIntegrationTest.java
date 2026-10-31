package com.wellnessz.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.wellnessz.gateway.connector.ConnectorRegistry;
import com.wellnessz.gateway.connector.TcpListenerConnector;
import com.wellnessz.gateway.connector.mllp.MllpFraming;
import com.wellnessz.gateway.identity.IdentityStore;
import com.wellnessz.gateway.ops.HeartbeatService;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.support.Hashes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end test of the edge runtime against a mock engine (JDK HttpServer): zero-touch enrolment with certificate
 * proof and simulated ingress thumbprint, MLLP capture with the core's ACK relayed, an outage with local AA and
 * encrypted queuing, replay through the batch API with the original Idempotency-Keys, a lost receipt without
 * duplication, the folder watcher, heartbeat with token rotation and isolation commands, and the localhost status page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GatewayIntegrationTest {

    static final MockEngine ENGINE;
    static final Path ROOT;
    static final Path KEYSTORE;

    static {
        try {
            ENGINE = new MockEngine();
            ROOT = Files.createTempDirectory("wz-gw-it");
            KEYSTORE = TestPki.clientKeyStore(ROOT.resolve("pki"), "EP-TST-GW-01");
            Files.createDirectories(ROOT.resolve("export"));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("wz.gateway.core-url", ENGINE::url);
        r.add("wz.gateway.data-dir", () -> ROOT.resolve("data").toString());
        r.add("wz.gateway.enrolment.token", () -> MockEngine.ENROLMENT_TOKEN);
        r.add("wz.gateway.tls.key-store", KEYSTORE::toString);
        r.add("wz.gateway.tls.key-store-password", () -> TestPki.PASSWORD);
        r.add("wz.gateway.tls.simulate-ingress", () -> "true");
        r.add("wz.gateway.forwarder.poll-interval", () -> "PT0.1S");
        r.add("wz.gateway.forwarder.initial-backoff", () -> "PT0.1S");
        r.add("wz.gateway.forwarder.max-backoff", () -> "PT0.3S");
        r.add("wz.gateway.mllp[0].id", () -> "mllp-vitals");
        r.add("wz.gateway.mllp[0].device-instance-id", () -> "DI-TST-VITALS-01");
        r.add("wz.gateway.mllp[0].bind-address", () -> "127.0.0.1");
        r.add("wz.gateway.mllp[0].port", () -> "0");
        r.add("wz.gateway.mllp[0].sending-applications[0]", () -> "CONNEX");
        r.add("wz.gateway.watchers[0].id", () -> "spiro-export");
        r.add("wz.gateway.watchers[0].device-instance-id", () -> "DI-TST-SPIRO-01");
        r.add("wz.gateway.watchers[0].path", () -> ROOT.resolve("export").toString());
        r.add("wz.gateway.watchers[0].archive", () -> ROOT.resolve("archive").toString());
        r.add("wz.gateway.watchers[0].stability-seconds", () -> "0");
        r.add("wz.gateway.watchers[0].poll-interval", () -> "PT0.2S");
    }

    @AfterAll
    static void stopEngine() {
        ENGINE.close();
    }

    @Autowired
    ConnectorRegistry connectors;
    @Autowired
    EncryptedJournal journal;
    @Autowired
    IdentityStore identities;
    @Autowired
    HeartbeatService heartbeat;
    @Autowired
    GatewayRuntime runtime;

    @LocalServerPort
    int statusPort;

    private final JsonMapper json = JsonMapper.builder().build();

    private static String oru(String controlId) {
        return "MSH|^~\\&|CONNEX|LHR-OH|WZ|WELLNESSZ|20261031101500||ORU^R01^ORU_R01|" + controlId + "|P|2.6\r"
                + "PID|1||SYN-0042^^^WZ||Synthetic^Alex\rOBX|1|NM|8867-4^Heart rate^LN||72|/min|||||F\r";
    }

    private String send(String message) throws IOException {
        int port = ((TcpListenerConnector) connectors.byId("mllp-vitals").orElseThrow()).localPort();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(15_000);
            OutputStream out = s.getOutputStream();
            out.write(MllpFraming.encode(message.getBytes(StandardCharsets.ISO_8859_1)));
            out.flush();
            byte[] ack = new MllpFraming.Reader(new BufferedInputStream(s.getInputStream()), 1 << 20).next();
            return new String(ack, StandardCharsets.ISO_8859_1);
        }
    }

    @Test
    @Order(1)
    void enrolsZeroTouchAndStoresTheIdentityEncrypted() throws Exception {
        await().atMost(Duration.ofSeconds(10)).until(runtime::ready);
        assertThat(identities.current().endpointId()).isEqualTo(MockEngine.ENDPOINT_ID);
        assertThat(ENGINE.enrolments).hasSize(1);
        JsonNode req = ENGINE.enrolments.get(0);
        assertThat(req.path("proof").asString()).isNotBlank();
        assertThat(ENGINE.enrolmentHeaders.get(0).get("x-client-cert-sha256")).matches("[0-9a-f]{64}");
        String stored = Files.readString(ROOT.resolve("data").resolve("identity.enc"));
        assertThat(stored).doesNotContain("device-token-initial").doesNotContain(MockEngine.ENDPOINT_ID);
        // every authenticated call from now on must carry the bound thumbprint
        ENGINE.expectedThumbprint = ENGINE.enrolmentHeaders.get(0).get("x-client-cert-sha256");
    }

    @Test
    @Order(2)
    void onlineMllpMessageIsForwardedAndTheEnginesAckRelayed() throws Exception {
        String ack = send(oru("CTRL-1"));
        assertThat(ack).contains("|WZ-ENGINE|").contains("MSA|AA|CTRL-1");
        MockEngine.Delivery d = ENGINE.deliveries("/device-ingest/hl7v2").get(0);
        assertThat(d.idempotencyKey()).isEqualTo(MockEngine.ENDPOINT_ID + ":1");
        assertThat(d.headers()).containsEntry("x-device-instance", "DI-TST-VITALS-01")
                .containsEntry("content-type", "application/hl7-v2").containsKey("x-edge-clock");
        assertThat(d.text()).contains("Synthetic^Alex");
        await().atMost(Duration.ofSeconds(5)).until(() -> journal.stats().depth() == 0);
    }

    @Test
    @Order(3)
    void senderOutsideTheAllowListIsRejectedWithAr() throws Exception {
        String ack = send(oru("CTRL-X").replace("CONNEX", "ROGUE"));
        assertThat(ack).contains("|WZ-GATEWAY|").contains("MSA|AR|CTRL-X");
        assertThat(ENGINE.deliveries("/device-ingest/hl7v2")).hasSize(1);
    }

    @Test
    @Order(4)
    void outageIsBridgedByTheEncryptedQueueAndReplayedWithoutLossOrDuplication() throws Exception {
        ENGINE.mode = MockEngine.Mode.DOWN_503;
        for (int i = 2; i <= 4; i++) {
            String ack = send(oru("CTRL-" + i));
            assertThat(ack).contains("|WZ-GATEWAY|").contains("MSA|AA|CTRL-" + i).contains("Stored for forwarding");
        }
        assertThat(journal.stats().depth()).isEqualTo(3);
        try (var files = Files.walk(ROOT.resolve("data").resolve("queue"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(f, StandardCharsets.ISO_8859_1)).doesNotContain("Synthetic");
            }
        }
        // the engine comes back but loses the first receipt: the gateway must resend with the same keys
        ENGINE.mode = MockEngine.Mode.STORE_THEN_503;
        await().atMost(Duration.ofSeconds(15)).until(() -> journal.stats().depth() == 0);
        assertThat(ENGINE.uniqueKeys).contains(MockEngine.ENDPOINT_ID + ":2", MockEngine.ENDPOINT_ID + ":3",
                MockEngine.ENDPOINT_ID + ":4");
        long hl7Unique = ENGINE.deliveries("/device-ingest/hl7v2").stream()
                .map(MockEngine.Delivery::idempotencyKey).distinct().count();
        assertThat(hl7Unique).isEqualTo(4); // CTRL-1..4, each exactly once from the engine's point of view
        assertThat(ENGINE.deliveries("/device-ingest/hl7v2").stream().filter(MockEngine.Delivery::viaBatch))
                .allSatisfy(d -> assertThat(d.headers()).containsEntry("x-replayed", "true"));
    }

    @Test
    @Order(5)
    void vendorExportFileIsForwardedWithItsHashAsIdempotencyKey() throws Exception {
        byte[] csv = "subject,fev1\nSYN-0042,3.10\n".getBytes(StandardCharsets.UTF_8);
        Files.write(ROOT.resolve("export").resolve("spiro-0042.csv"), csv);
        String sha = Hashes.sha256Hex(csv);
        await().atMost(Duration.ofSeconds(10)).until(() -> ENGINE.uniqueKeys.contains(sha));
        MockEngine.Delivery d = ENGINE.deliveries("/device-ingest/files").get(0);
        assertThat(d.headers()).containsEntry("x-file-sha256", sha).containsEntry("x-file-format", "CSV")
                .containsEntry("x-file-name", "spiro-0042.csv");
        assertThat(d.body()).isEqualTo(csv);
        assertThat(ROOT.resolve("export").resolve("spiro-0042.csv")).exists();
    }

    @Test
    @Order(6)
    void heartbeatReportsStateAndExecutesRotateTokenCommand() throws Exception {
        ENGINE.validTokens.add("device-token-rotated-0123456789abcdef");
        ENGINE.pendingCommands.add((tools.jackson.databind.node.ObjectNode) json.readTree(
                "{\"commandId\":\"CMD-1\",\"type\":\"ROTATE_TOKEN\",\"payload\":{\"endpointToken\":"
                        + "\"device-token-rotated-0123456789abcdef\",\"tokenExpiresAt\":\"2027-01-29T00:00:00Z\"}}"));
        assertThat(heartbeat.send()).isTrue();
        JsonNode hb = ENGINE.heartbeats.get(ENGINE.heartbeats.size() - 1);
        assertThat(hb.path("configVersion").asString()).isEqualTo("cfg-1");
        assertThat(hb.path("queue").path("depth").asInt()).isZero();
        // DRIFT (ingress simulation, plain http core URL); FAIL when the build runs as root (e.g. in a CI container)
        assertThat(hb.path("hardening").path("status").asString()).isIn("DRIFT", "FAIL");
        assertThat(hb.path("hardening").path("findings").toString()).contains("INGRESS_SIMULATION_ENABLED");
        assertThat(hb.path("devices").toString()).contains("DI-TST-VITALS-01").contains("DI-TST-SPIRO-01");
        assertThat(hb.path("listeners").get(0).path("tls").asBoolean()).isFalse();
        assertThat(hb.toString()).doesNotContain("Synthetic").doesNotContain("SYN-0042");
        assertThat(identities.current().endpointToken()).isEqualTo("device-token-rotated-0123456789abcdef");
        assertThat(ENGINE.commandResults).anyMatch(r -> r.contains("CMD-1") && r.contains("DONE"));
        // the old token is withdrawn: forwarding keeps working with the rotated one
        ENGINE.validTokens.remove("device-token-initial-0123456789abcdef");
        assertThat(send(oru("CTRL-5"))).contains("|WZ-ENGINE|").contains("MSA|AA|CTRL-5");
    }

    @Test
    @Order(7)
    void statusPageIsServedOnLocalhostWithoutPhi() throws Exception {
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + statusPort + "/")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("WellnessZ device gateway").contains(MockEngine.ENDPOINT_ID)
                .contains("mllp-vitals").doesNotContain("Synthetic").doesNotContain("device-token");
        assertThat(page.headers().firstValue("Content-Security-Policy")).isPresent();
        HttpResponse<String> status = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + statusPort + "/status")).build(), HttpResponse.BodyHandlers.ofString());
        JsonNode s = json.readTree(status.body());
        assertThat(s.path("enrolled").asBoolean()).isTrue();
        assertThat(s.path("coreOnline").asBoolean()).isTrue();
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + statusPort + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.body()).contains("\"gateway\"");
        HttpResponse<String> prom = http.send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + statusPort + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(prom.body()).contains("wz_gateway_queue_depth").contains("wz_gateway_messages_received_total");
    }

    @Test
    @Order(8)
    void isolateCommandQuarantinesTheQueueAndStopsForwarding() throws Exception {
        ENGINE.pendingCommands.add((tools.jackson.databind.node.ObjectNode) json.readTree(
                "{\"commandId\":\"CMD-2\",\"type\":\"ISOLATE\"}"));
        assertThat(heartbeat.send()).isTrue();
        assertThat(journal.quarantined()).isTrue();
        int before = ENGINE.deliveries.size();
        String ack = send(oru("CTRL-6"));
        assertThat(ack).contains("|WZ-GATEWAY|").contains("MSA|AA|CTRL-6"); // stored locally only
        Thread.sleep(500);
        assertThat(ENGINE.deliveries).hasSize(before);
        assertThat(journal.stats().depth()).isEqualTo(1);
    }
}
