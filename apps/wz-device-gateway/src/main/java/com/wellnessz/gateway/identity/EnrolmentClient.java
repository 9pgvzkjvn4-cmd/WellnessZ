package com.wellnessz.gateway.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.core.Backoff;
import com.wellnessz.gateway.core.CoreClient;
import com.wellnessz.gateway.core.CoreResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Zero-touch enrolment client (contract §15.2): exchanges the one-time enrolment token, the client certificate and a
 * proof of possession ({@code "enrol:" + token + ":" + thumbprint} signed with the certificate key) for the endpoint id
 * and the per-endpoint device token. Transient failures (I/O, 429, 5xx) are retried with exponential back-off;
 * deterministic refusals (invalid / expired token, untrusted certificate …) stop at once.
 */
public class EnrolmentClient {

    public static final String ENROL_PATH = "/device-enrolment/enrol";
    private static final Logger log = LoggerFactory.getLogger(EnrolmentClient.class);

    /** Pluggable sleep so the retry loop can be tested without waiting. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    private final CoreClient core;
    private final ClientCertificate certificate;
    private final GatewayProperties props;
    private final Supplier<Map<String, Object>> hardening;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();

    public EnrolmentClient(CoreClient core, ClientCertificate certificate, GatewayProperties props,
                           Supplier<Map<String, Object>> hardening, Clock clock) {
        this.core = core;
        this.certificate = certificate;
        this.props = props;
        this.hardening = hardening;
        this.clock = clock;
    }

    public EndpointIdentity enrolWithRetry(String token, Backoff backoff, int maxAttempts, Sleeper sleeper)
            throws EnrolmentException, InterruptedException {
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return enrol(token);
            } catch (EnrolmentException e) {
                if (!e.retryable() || (maxAttempts > 0 && attempt >= maxAttempts)) {
                    throw e;
                }
                Duration delay = backoff.next(e.getCause() instanceof RetryAfter ra ? ra.delay : null);
                log.warn("enrolment attempt {} failed ({}); retrying in {} ms", attempt, e.code(), delay.toMillis());
                sleeper.sleep(delay);
            }
        }
    }

    public EndpointIdentity enrol(String token) throws EnrolmentException {
        if (token == null || token.isBlank()) {
            throw new EnrolmentException("enrolment_token_missing", 0, false, null);
        }
        String thumbprint = certificate.thumbprint();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrolmentToken", token.trim());
        body.put("endpointKind", props.endpointKind());
        if (certificate.present()) {
            body.put("certificatePem", certificate.pem());
            body.put("proof", certificate.sign("enrol:" + token.trim() + ":" + thumbprint));
        }
        body.put("hostname", hostname());
        body.put("platform", Map.of(
                "os", System.getProperty("os.name", "unknown"),
                "osVersion", System.getProperty("os.version", "unknown"),
                "agentVersion", agentVersion()));
        body.put("hardening", hardening.get());
        CoreResponse r;
        try {
            r = core.sendAnonymous("POST", ENROL_PATH, "application/json", Map.of(),
                    json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EnrolmentException("core_unreachable", 0, true, e);
        }
        if (r.status() == 201 || r.status() == 200) {
            JsonNode n = json.readTree(r.text());
            String endpointId = n.path("endpointId").asString(null);
            String endpointToken = n.path("endpointToken").asString(null);
            if (endpointId == null || endpointToken == null || !endpointId.matches("[A-Za-z0-9._:-]{1,64}")) {
                throw new EnrolmentException("enrolment_response_invalid", r.status(), false, null);
            }
            String expires = n.path("tokenExpiresAt").asString(null);
            log.info("enrolled as endpoint {}", endpointId);
            return new EndpointIdentity(endpointId, endpointToken, expires == null ? null : Instant.parse(expires),
                    clock.instant(), thumbprint);
        }
        String code = r.problemCode().orElse("http_" + r.status());
        Throwable cause = r.status() == 429
                ? new RetryAfter(Backoff.parseRetryAfter(r.header("Retry-After").orElse(null))) : null;
        throw new EnrolmentException(code, r.status(), r.retryable(), cause);
    }

    private String hostname() {
        if (props.hostname() != null && !props.hostname().isBlank()) {
            return props.hostname();
        }
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            return "unknown";
        }
    }

    static String agentVersion() {
        Package p = EnrolmentClient.class.getPackage();
        String v = p == null ? null : p.getImplementationVersion();
        return v == null ? "dev" : v;
    }

    /** Carries the server's {@code Retry-After} through the exception chain. */
    private static final class RetryAfter extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Duration delay;

        RetryAfter(Duration delay) {
            super("retry after", null, false, false);
            this.delay = delay;
        }
    }
}
