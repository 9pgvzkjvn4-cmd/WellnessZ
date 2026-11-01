package com.wellnessz.e31.web;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e31.driver.DriverPackage;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.pipeline.IngestService;
import com.wellnessz.e31.store.MessageStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.e31.support.Ids;
import com.wellnessz.e31.support.RateLimitedException;

import tools.jackson.databind.JsonNode;

/**
 * Vendor-cloud webhooks (P6, contract section 7): no client certificate (vendor clouds cannot present one); the connector's
 * HMAC-SHA256 over {@code timestamp + "." + rawBody} is verified with the webhook secret resolved through
 * {@code DeviceSecretStore} (E34) – constant-time comparison, 300-second replay window, per-connector rate limit 600/min.
 * Failures answer 401 {@code signature_invalid} / {@code timestamp_out_of_window} and raise {@code WEBHOOK_SIGNATURE_FAILED}.
 */
@RestController("e31WebhookController")
@RequestMapping("/api/v1/device-webhooks")
public class WebhookController {

    static final int RATE_PER_MINUTE = 600;

    private final DeviceSpis spis;
    private final DriverRuntime drivers;
    private final IngestHandler handler;
    private final IngestService ingest;
    private final MessageStore messages;
    private final ModuleRegistry modules;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WebhookController(DeviceSpis spis, DriverRuntime drivers, IngestHandler handler, IngestService ingest, MessageStore messages,
                             ModuleRegistry modules, JdbcTemplate jdbc, Clock clock) {
        this.spis = spis;
        this.drivers = drivers;
        this.handler = handler;
        this.ingest = ingest;
        this.messages = messages;
        this.modules = modules;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostMapping(value = "/{connectorId}", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Object> receive(@PathVariable String connectorId, @RequestBody byte[] body, HttpServletRequest req) {
        modules.requireEnabled("E31");
        if (!connectorId.matches("[A-Za-z0-9._\\-]{1,64}")) {
            throw ApiException.notFound("Connector");
        }
        DeviceCatalogueLookup.DeviceInstance instance = spis.catalogue().instance(connectorId)
                .filter(i -> i.option() == DeviceCodes.Option.P6_CLOUD_API && i.published())
                .orElseThrow(() -> ApiException.notFound("Connector"));
        String endpoint = "VENDOR:" + connectorId;
        if (messages.countForEndpointSince(endpoint, clock.instant().minusSeconds(60)) >= RATE_PER_MINUTE) {
            throw new RateLimitedException("Connector rate limit exceeded", 60);
        }
        DriverPackage pkg = drivers.forInstance(instance, null);
        JsonNode sig = pkg.protocol() == null ? null : pkg.protocol().get("signature");
        String header = sig == null ? "X-Signature" : sig.path("header").asString("X-Signature");
        String tsHeader = sig == null ? "X-Timestamp" : sig.path("timestampHeader").asString("X-Timestamp");
        long window = sig == null ? 300 : sig.path("replayWindowSeconds").asLong(300);
        String secretRefParam = sig == null ? "PARAMETER:webhookSecretRef" : sig.path("secretRef").asString("PARAMETER:webhookSecretRef");
        String signature = req.getHeader(header);
        String timestamp = req.getHeader(tsHeader);
        Instant ts = parseTimestamp(timestamp);
        if (ts == null || Duration.between(ts, clock.instant()).abs().getSeconds() > window) {
            fail(instance, "timestamp_out_of_window");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "timestamp_out_of_window", "The webhook timestamp is outside the replay window");
        }
        String ref = secretRefParam.startsWith("PARAMETER:") ? String.valueOf(instance.parameters().get(secretRefParam.substring(10))) : secretRefParam;
        Optional<char[]> secret = spis.secrets().resolve(ref, "WEBHOOK_HMAC");
        if (secret.isEmpty() || signature == null || !valid(secret.get(), timestamp, body, signature)) {
            secret.ifPresent(s -> Arrays.fill(s, '\0'));
            fail(instance, "signature_invalid");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "signature_invalid", "The webhook signature is invalid");
        }
        Arrays.fill(secret.get(), '\0');
        String nonce = timestamp + ":" + Ids.sha256Hex(body).substring(0, 64);
        try {
            jdbc.update("insert into deviceint.webhook_nonce (connector_id, nonce, received_at) values (?, ?, ?)", connectorId,
                    nonce.length() > 140 ? nonce.substring(0, 140) : nonce, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException replay) {
            // same request again inside the window: answered with the original receipt (idempotency below)
        }
        IngestService.Source s = new IngestService.Source(endpoint, instance.tenantCode() == null ? EngineSettings.DEFAULT_TENANT : instance.tenantCode(),
                instance.siteCode(), Set.of(connectorId), false);
        IngestService.Receipt r = ingest.acquire(new IngestService.Acquire(s, "WEBHOOK", "application/json", "WH:" + nonce, null, ts, null, connectorId,
                null, false, body, Map.of(), false, false, null, null, false));
        ResponseEntity.BodyBuilder b = ResponseEntity.status(HttpStatus.ACCEPTED);
        if ("DUPLICATE".equals(r.status())) {
            b.header("Idempotent-Replayed", "true");
        }
        return b.body(Map.of("messageId", r.messageId(), "status", r.status()));
    }

    public static Instant parseTimestamp(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            if (v.matches("\\d{9,11}")) {
                return Instant.ofEpochSecond(Long.parseLong(v));
            }
            if (v.matches("\\d{12,14}")) {
                return Instant.ofEpochMilli(Long.parseLong(v));
            }
            return Instant.parse(v);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Hex HMAC-SHA256 over {@code timestamp + "." + rawBody}, compared in constant time. */
    public static boolean valid(char[] secret, String timestamp, byte[] body, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            byte[] key = new String(secret).getBytes(StandardCharsets.UTF_8);
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            Arrays.fill(key, (byte) 0);
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            String given = signature.strip();
            if (given.startsWith("sha256=")) {
                given = given.substring(7);
            }
            return Ids.constantTimeEquals(expected, given);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return false;
        }
    }

    void fail(DeviceCatalogueLookup.DeviceInstance instance, String reason) {
        try {
            spis.securityEvents().publish(new DeviceSecurityEventPublisher.DeviceSecurityEvent("WEBHOOK_SIGNATURE_FAILED", DeviceCodes.Severity.HIGH,
                    "VENDOR:" + instance.deviceInstanceId(), instance.deviceInstanceId(), instance.siteCode(), "vendor:" + instance.deviceInstanceId(),
                    "E31", reason, Map.of("protocol", "HTTPS"), clock.instant()));
        } catch (RuntimeException ignored) {
            // the 401 is returned regardless of event delivery
        }
    }

    public IngestHandler handler() {
        return handler;
    }
}
