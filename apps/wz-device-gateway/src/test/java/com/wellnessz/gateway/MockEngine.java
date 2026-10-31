package com.wellnessz.gateway;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * In-process stand-in for the core ingest API (JDK HttpServer): enrolment, authenticated ingest with Idempotency-Key
 * de-duplication, batch, heartbeat with commands, config, command results. Switchable outage modes.
 */
public final class MockEngine implements AutoCloseable {

    public static final String ENDPOINT_ID = "EP-TST-GW-01";
    public static final String ENROLMENT_TOKEN = "enrol-token-0123456789abcdef0123456789abcdef";

    /** One ingest delivery as seen by the engine. */
    public record Delivery(String path, String idempotencyKey, Map<String, String> headers, byte[] body,
                           boolean viaBatch) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public enum Mode { UP, DOWN_503, STORE_THEN_503 }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    public final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
    public final Set<String> uniqueKeys = Collections.synchronizedSet(new LinkedHashSet<>());
    public final AtomicInteger duplicates = new AtomicInteger();
    public final List<JsonNode> enrolments = new CopyOnWriteArrayList<>();
    public final List<Map<String, String>> enrolmentHeaders = new CopyOnWriteArrayList<>();
    public final List<JsonNode> heartbeats = new CopyOnWriteArrayList<>();
    public final List<String> commandResults = new CopyOnWriteArrayList<>();
    public final ConcurrentLinkedQueue<ObjectNode> pendingCommands = new ConcurrentLinkedQueue<>();
    public final Set<String> validTokens = Collections.synchronizedSet(new LinkedHashSet<>());
    public final AtomicInteger batchCalls = new AtomicInteger();
    public volatile Mode mode = Mode.UP;
    public volatile int enrolFailuresBeforeSuccess;
    public volatile String enrolRefusalCode;
    public volatile String expectedThumbprint;
    /** Ingest path answered 422 unknown_device (single and batch). */
    public volatile String rejectPath;
    /** All ingest calls answered 401 endpoint_isolated. */
    public volatile boolean isolated;

    public MockEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 50);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/v1/", this::handle);
        server.start();
        validTokens.add("device-token-initial-0123456789abcdef");
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Delivery> deliveries(String path) {
        return deliveries.stream().filter(d -> d.path().equals(path)).toList();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String path = ex.getRequestURI().getPath().substring("/api/v1".length());
            byte[] body = ex.getRequestBody().readAllBytes();
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
            if (path.equals("/device-enrolment/enrol")) {
                enrol(ex, headers, body);
                return;
            }
            if (!ENDPOINT_ID.equals(headers.get("x-device-endpoint")) || headers.get("authorization") == null
                    || !validTokens.contains(headers.get("authorization").replace("DeviceToken ", ""))) {
                send(ex, 401, "application/problem+json", "{\"code\":\"bad_token\"}");
                return;
            }
            if (isolated && path.startsWith("/device-ingest/") && !path.equals("/device-ingest/heartbeat")) {
                send(ex, 401, "application/problem+json", "{\"code\":\"endpoint_isolated\"}");
                return;
            }
            if (expectedThumbprint != null && !expectedThumbprint.equals(headers.get("x-client-cert-sha256"))) {
                send(ex, 401, "application/problem+json", "{\"code\":\"mtls_required\"}");
                return;
            }
            switch (path) {
                case "/device-ingest/heartbeat" -> heartbeat(ex, body);
                case "/device-ingest/config" -> send(ex, 200, "application/json", "{\"endpointId\":\"" + ENDPOINT_ID
                        + "\",\"configVersion\":\"cfg-1\",\"role\":\"GATEWAY\",\"heartbeatSeconds\":60,"
                        + "\"instances\":[],\"queue\":{\"maxBytes\":1073741824,\"retentionHours\":72},"
                        + "\"logLevel\":\"INFO\"}");
                case "/device-ingest/batch" -> batch(ex, headers, body);
                default -> {
                    if (path.startsWith("/device-ingest/commands/")) {
                        commandResults.add(path + " " + new String(body, StandardCharsets.UTF_8));
                        send(ex, 204, null, "");
                    } else if (path.startsWith("/device-ingest/")) {
                        single(ex, path, headers, body);
                    } else {
                        send(ex, 404, "application/problem+json", "{\"code\":\"not_found\"}");
                    }
                }
            }
        }
    }

    private void enrol(HttpExchange ex, Map<String, String> headers, byte[] body) throws IOException {
        JsonNode req = json.readTree(body);
        enrolments.add(req);
        enrolmentHeaders.add(headers);
        if (enrolFailuresBeforeSuccess > 0) {
            enrolFailuresBeforeSuccess--;
            send(ex, 503, "application/problem+json", "{\"code\":\"unavailable\"}");
            return;
        }
        if (enrolRefusalCode != null) {
            send(ex, 401, "application/problem+json", "{\"code\":\"" + enrolRefusalCode + "\"}");
            return;
        }
        if (!ENROLMENT_TOKEN.equals(req.path("enrolmentToken").asString(""))) {
            send(ex, 401, "application/problem+json", "{\"code\":\"enrolment_token_invalid\"}");
            return;
        }
        send(ex, 201, "application/json", "{\"endpointId\":\"" + ENDPOINT_ID
                + "\",\"endpointToken\":\"device-token-initial-0123456789abcdef\",\"tokenExpiresAt\":\""
                + Instant.now().plusSeconds(86400 * 90) + "\",\"configUrl\":\"/api/v1/device-ingest/config\","
                + "\"serverTime\":\"" + Instant.now() + "\"}");
    }

    private void heartbeat(HttpExchange ex, byte[] body) throws IOException {
        heartbeats.add(json.readTree(body));
        ObjectNode resp = json.createObjectNode();
        resp.put("serverTime", Instant.now().toString());
        resp.put("configVersion", "cfg-1");
        ArrayNode cmds = resp.putArray("commands");
        ObjectNode c;
        while ((c = pendingCommands.poll()) != null) {
            cmds.add(c);
        }
        send(ex, 200, "application/json", json.writeValueAsString(resp));
    }

    private void single(HttpExchange ex, String path, Map<String, String> headers, byte[] body) throws IOException {
        if (mode == Mode.DOWN_503) {
            send(ex, 503, "application/problem+json", "{\"code\":\"unavailable\"}");
            return;
        }
        String key = headers.get("idempotency-key");
        if (path.equals(rejectPath)) {
            send(ex, 422, "application/problem+json", "{\"code\":\"unknown_device\"}");
            return;
        }
        boolean dup = record(path, key, headers, body, false);
        if (mode == Mode.STORE_THEN_503) {
            mode = Mode.UP; // the receipt is "lost" once
            send(ex, 503, "application/problem+json", "{\"code\":\"unavailable\"}");
            return;
        }
        if (path.equals("/device-ingest/hl7v2")) {
            send(ex, 200, "text/plain", ack(body));
        } else if (path.equals("/device-ingest/attachments")) {
            send(ex, 201, "application/json", "{\"archiveId\":\"ARC-1\",\"sha256\":\"x\",\"sizeBytes\":" + body.length + "}");
        } else {
            send(ex, 202, "application/json", receipt(key, dup));
        }
    }

    private void batch(HttpExchange ex, Map<String, String> headers, byte[] body) throws IOException {
        batchCalls.incrementAndGet();
        if (mode == Mode.DOWN_503) {
            send(ex, 503, "application/problem+json", "{\"code\":\"unavailable\"}");
            return;
        }
        JsonNode req = json.readTree(body);
        ObjectNode resp = json.createObjectNode();
        ArrayNode results = resp.putArray("results");
        for (JsonNode item : req.path("items")) {
            String key = item.path("idempotencyKey").asString();
            String kind = item.path("kind").asString();
            String text = item.path("body").asString();
            if (pathOf(kind).equals(rejectPath)) {
                ObjectNode r = results.addObject();
                r.put("idempotencyKey", key);
                r.put("httpStatus", 422);
                r.putObject("problem").put("code", "unknown_device");
                continue;
            }
            byte[] b = "FILE".equals(kind) ? Base64.getDecoder().decode(text) : text.getBytes(StandardCharsets.UTF_8);
            Map<String, String> h = new LinkedHashMap<>();
            item.path("headers").properties().forEach(e -> h.put(e.getKey().toLowerCase(java.util.Locale.ROOT),
                    e.getValue().asString()));
            boolean dup = record(pathOf(kind), key, h, b, true);
            ObjectNode r = results.addObject();
            r.put("idempotencyKey", key);
            r.put("httpStatus", 202);
            r.set("receipt", json.readTree(receipt(key, dup)));
        }
        if (mode == Mode.STORE_THEN_503) {
            mode = Mode.UP;
            send(ex, 503, "application/problem+json", "{\"code\":\"unavailable\"}");
            return;
        }
        send(ex, 200, "application/json", json.writeValueAsString(resp));
    }

    private static String pathOf(String kind) {
        return switch (kind) {
            case "HL7V2" -> "/device-ingest/hl7v2";
            case "FILE" -> "/device-ingest/files";
            case "BLE" -> "/device-ingest/ble";
            case "SERIAL" -> "/device-ingest/serial";
            case "POCT1A" -> "/device-ingest/poct1a";
            case "FHIR" -> "/device-ingest/fhir";
            default -> "/device-ingest/" + kind.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private boolean record(String path, String key, Map<String, String> headers, byte[] body, boolean viaBatch) {
        deliveries.add(new Delivery(path, key, headers, body, viaBatch));
        boolean dup = !uniqueKeys.add(key);
        if (dup) {
            duplicates.incrementAndGet();
        }
        return dup;
    }

    private static String receipt(String key, boolean dup) {
        return "{\"messageId\":\"MSG-" + Math.abs(key.hashCode()) + "\",\"status\":\"" + (dup ? "DUPLICATE" : "ACCEPTED")
                + "\",\"receivedAt\":\"" + Instant.now() + "\"}";
    }

    private static String ack(byte[] hl7) {
        String text = new String(hl7, StandardCharsets.ISO_8859_1);
        String msh = text.split("\r")[0];
        String[] f = msh.split("\\|", -1);
        String control = f.length > 9 ? f[9] : "";
        return "MSH|^~\\&|WZ-ENGINE|WELLNESSZ|" + f[2] + "|" + f[3] + "|20261031120000||ACK^R01^ACK|ENG-1|P|2.5\r"
                + "MSA|AA|" + control + "\r";
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        ex.sendResponseHeaders(status, status == 204 ? -1 : b.length);
        if (status != 204) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    /** Collected keys of all deliveries, in arrival order. */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        deliveries.forEach(d -> out.add(d.idempotencyKey()));
        return out;
    }
}
