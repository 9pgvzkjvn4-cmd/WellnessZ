package com.wellnessz.gateway.connector.ble;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.AbstractConnector;
import com.wellnessz.gateway.connector.IngestSink;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueFullException;
import com.wellnessz.gateway.queue.QueueItem;
import com.wellnessz.gateway.support.Hashes;
import com.wellnessz.gateway.support.TlsSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * BLE intake (P3) for the gateway's own BLE stack helper and for a mobile / kiosk relay that has no endpoint identity
 * of its own: {@code POST /ble/records} with the contract's {@code {records:[…]}} body (≤ 100 records), authenticated
 * with a relay key ({@code X-Relay-Key}, constant-time comparison). Raw MAC addresses never leave the edge: a
 * {@code peripheral.address} is replaced by {@code addressHash = SHA-256(MAC + endpoint salt)} before queuing. The bonded
 * peripheral allow-list (from configuration or the core's {@code GatewayConfig}) is enforced. Forwarded as
 * {@code POST /device-ingest/ble}.
 */
public class BleIntakeServer extends AbstractConnector {

    private static final Logger log = LoggerFactory.getLogger(BleIntakeServer.class);
    private static final int MAX_BODY = 1024 * 1024;
    private static final Set<String> ENCODINGS = Set.of("HEX", "BASE64");

    private final GatewayProperties.Ble cfg;
    private final boolean allowPlaintext;
    private final String salt;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile List<String> allowList;
    private HttpServer server;
    private ExecutorService executor;

    public BleIntakeServer(GatewayProperties.Ble cfg, IngestSink sink, Clock clock, boolean allowPlaintext,
                           String salt) {
        super("ble-intake", null, sink, clock);
        if (cfg.relayKey() == null || cfg.relayKey().length() < 16) {
            throw new IllegalArgumentException("BLE intake requires wz.gateway.ble.relay-key (>= 16 characters)");
        }
        this.cfg = cfg;
        this.allowPlaintext = allowPlaintext;
        this.salt = cfg.addressSalt() != null && !cfg.addressSalt().isBlank() ? cfg.addressSalt() : salt;
        this.allowList = cfg.peripheralAllowList();
    }

    @Override
    public String type() {
        return "BLE";
    }

    public void allowList(List<String> list) {
        this.allowList = list == null ? List.of() : List.copyOf(list);
    }

    public int localPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    @Override
    public void start() throws IOException {
        InetSocketAddress addr = new InetSocketAddress(InetAddress.getByName(cfg.bindAddress()), cfg.port());
        if (cfg.tls().enabled()) {
            HttpsServer https = HttpsServer.create(addr, 50);
            https.setHttpsConfigurator(new HttpsConfigurator(TlsSupport.serverContext(cfg.tls())));
            server = https;
        } else {
            if (!TlsSupport.plaintextPermitted(cfg.bindAddress(), cfg.tls(), allowPlaintext)) {
                throw new IllegalStateException("BLE intake on a non-loopback address requires TLS "
                        + "(or the documented plaintext exception)");
            }
            server = HttpServer.create(addr, 50);
        }
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/ble/records", this::handle);
        server.start();
        running = true;
        log.info("BLE intake on {}:{} (tls={})", cfg.bindAddress(), localPort(), cfg.tls().enabled());
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void handle(HttpExchange ex) throws IOException {
        try (ex) {
            if (!"POST".equals(ex.getRequestMethod())) {
                respond(ex, 405, problem("method_not_allowed"));
                return;
            }
            if (!Hashes.constantTimeEquals(ex.getRequestHeaders().getFirst("X-Relay-Key"), cfg.relayKey())) {
                error("relay_key_invalid");
                respond(ex, 401, problem("relay_key_invalid"));
                return;
            }
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readNBytes(MAX_BODY + 1);
            }
            if (body.length > MAX_BODY) {
                respond(ex, 413, problem("payload_too_large"));
                return;
            }
            Result r = accept(body);
            respond(ex, r.status(), r.body());
        }
    }

    /** Outcome of an intake request. */
    public record Result(int status, String body) {
    }

    /** Validates, pseudonymises and queues one request body. Public for tests. */
    public Result accept(byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (tools.jackson.core.JacksonException e) {
            return new Result(400, problem("validation_failed"));
        }
        JsonNode records = root.path("records");
        if (!records.isArray() || records.isEmpty() || records.size() > 100) {
            return new Result(400, problem("validation_failed"));
        }
        ObjectNode out = json.createObjectNode();
        ArrayNode arr = out.putArray("records");
        for (JsonNode rec : records) {
            ObjectNode clean = normalise(rec);
            if (clean == null) {
                return new Result(400, problem("validation_failed"));
            }
            List<String> allow = allowList;
            String hash = clean.path("peripheral").path("addressHash").asString("");
            String bond = clean.path("peripheral").path("bondId").asString("");
            if (!allow.isEmpty() && !allow.contains(hash) && !allow.contains(bond)) {
                error("peripheral_not_allowed");
                return new Result(403, problem("peripheral_not_allowed"));
            }
            arr.add(clean);
        }
        try {
            QueueItem item = sink.capture(type(), ItemKind.BLE, null, "application/json", Map.of(),
                    json.writeValueAsString(out).getBytes(StandardCharsets.UTF_8), null);
            seen();
            return new Result(202, "{\"queued\":" + arr.size() + ",\"sequence\":" + item.sequence() + "}");
        } catch (QueueFullException e) {
            error("queue_full");
            return new Result(503, problem("queue_full"));
        }
    }

    private ObjectNode normalise(JsonNode rec) {
        JsonNode p = rec.path("peripheral");
        String encoding = rec.path("encoding").asString("").toUpperCase(Locale.ROOT);
        String value = rec.path("value").asString(null);
        String service = rec.path("service").asString(null);
        String characteristic = rec.path("characteristic").asString(null);
        String receivedAt = rec.path("receivedAt").asString(null);
        if (!p.isObject() || !ENCODINGS.contains(encoding) || value == null || value.length() > 8192
                || service == null || characteristic == null || receivedAt == null) {
            return null;
        }
        if (!validValue(encoding, value) || !service.matches("(0x)?[0-9A-Fa-f-]{4,36}")
                || !characteristic.matches("(0x)?[0-9A-Fa-f-]{4,36}")) {
            return null;
        }
        try {
            Instant.parse(receivedAt);
        } catch (DateTimeParseException e) {
            return null;
        }
        String addressHash = p.path("addressHash").asString(null);
        String address = p.path("address").asString(null);
        if (address != null) {
            String mac = address.toUpperCase(Locale.ROOT).replace('-', ':');
            if (!mac.matches("([0-9A-F]{2}:){5}[0-9A-F]{2}")) {
                return null;
            }
            addressHash = Hashes.sha256Hex(mac + salt);
        }
        if (addressHash == null || !addressHash.matches("[0-9a-f]{64}")) {
            return null;
        }
        ObjectNode n = json.createObjectNode();
        copyText(rec, n, "deviceInstanceId", 64);
        ObjectNode peripheral = n.putObject("peripheral");
        peripheral.put("addressHash", addressHash);
        copyText(p, peripheral, "name", 64);
        copyText(p, peripheral, "bondId", 64);
        n.put("service", service);
        n.put("characteristic", characteristic);
        n.put("encoding", encoding);
        n.put("value", value);
        n.put("receivedAt", receivedAt);
        copyText(rec, n, "sessionId", 64);
        copyText(rec, n, "queueEntryId", 64);
        copyText(rec, n, "operator", 64);
        return n;
    }

    private static boolean validValue(String encoding, String value) {
        return "HEX".equals(encoding) ? value.matches("([0-9A-Fa-f]{2})+") : value.matches("[A-Za-z0-9+/]+={0,2}");
    }

    private static void copyText(JsonNode from, ObjectNode to, String field, int max) {
        String v = from.path(field).asString(null);
        if (v != null && !v.isBlank()) {
            to.put(field, v.length() > max ? v.substring(0, max) : v);
        }
    }

    private static String problem(String code) {
        return "{\"type\":\"about:blank\",\"code\":\"" + code + "\"}";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", status >= 400 ? "application/problem+json" : "application/json");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }
}
