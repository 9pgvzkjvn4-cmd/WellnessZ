package io.intimed.api;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.pipeline.Envelope;
import io.intimed.engine.pipeline.Pipeline;
import io.intimed.engine.pipeline.PipelineResult;
import io.intimed.security.RateLimiter;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.TokenService;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Endpoints that authenticate themselves (no session): OAuth2 token (client secret), device enrolment (one-time token +
 * CSR), vendor-cloud webhooks (HMAC-SHA256 over timestamp.body, 300 s replay window, signature replay cache), JWKS.
 */
@RestController
public class PublicMachineController {
    private final TokenService tokens;
    private final EndpointService endpoints;
    private final Pipeline pipeline;
    private final DocumentStore store;
    private final SecretStore secrets;
    private final AuditService audit;
    private final RateLimiter limiter;
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    public PublicMachineController(TokenService tokens, EndpointService endpoints, Pipeline pipeline, DocumentStore store, SecretStore secrets, AuditService audit, RateLimiter limiter) {
        this.tokens = tokens;
        this.endpoints = endpoints;
        this.pipeline = pipeline;
        this.store = store;
        this.secrets = secrets;
        this.audit = audit;
        this.limiter = limiter;
    }

    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> token(@RequestParam("grant_type") String grant, @RequestParam(value = "scope", required = false) String scope,
                                     @RequestParam(value = "client_id", required = false) String cid, @RequestParam(value = "client_secret", required = false) String csec,
                                     @RequestHeader(value = "Authorization", required = false) String auth, HttpServletRequest req) {
        if (!limiter.allow("token:" + req.getRemoteAddr(), 5, 30)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "too many token requests");
        if (!"client_credentials".equals(grant)) throw ApiException.bad("unsupported_grant_type", "only client_credentials is supported");
        if (auth != null && auth.startsWith("Basic ")) {
            String[] p = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8).split(":", 2);
            cid = p[0];
            csec = p.length > 1 ? p[1] : "";
        }
        return tokens.token(cid, csec, scope);
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return new JWKSet(new ECKey.Builder(Curve.P_256, tokens.publicKey()).keyID("intimed-local-1").build()).toJSONObject();
    }

    public record EnrolRequest(String token, String csr) { }

    @PostMapping("/api/enrol")
    public Map<String, String> enrol(@RequestBody EnrolRequest r, HttpServletRequest req) {
        if (!limiter.allow("enrol:" + req.getRemoteAddr(), 0.2, 5)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "too many enrolment attempts");
        return endpoints.enrol(r.token(), r.csr());
    }

    /** P6 vendor-cloud webhook receiver for a published configuration. */
    @PostMapping("/api/webhooks/{tenant}/{configId}")
    public ResponseEntity<Object> webhook(@PathVariable String tenant, @PathVariable String configId, @RequestBody String body,
                                          @RequestHeader(value = "X-IntiMed-Timestamp", required = false) String ts,
                                          @RequestHeader(value = "X-IntiMed-Signature", required = false) String sig, HttpServletRequest req) {
        if (body.length() > 1_000_000) return ResponseEntity.status(413).body(Map.of("error", "payload too large"));
        if (!limiter.allow("webhook:" + tenant + "/" + configId, 50, 200)) return ResponseEntity.status(429).body(Map.of("error", "rate limited"));
        ObjectNode cfg = store.get(tenant, "config", configId).orElse(null);
        String ref = cfg == null ? "" : cfg.at("/params/webhookSecret").asString("");
        String secret = SecretStore.isRef(ref) ? secrets.get(tenant, SecretStore.name(ref)).orElse(null) : null;
        // identical response for unknown configuration and bad signature: no oracle
        if (secret == null || ts == null || sig == null || !sig.startsWith("v1=")) return unauthorized(tenant, configId, "missing signature or unknown configuration");
        long t;
        try { t = Long.parseLong(ts); } catch (NumberFormatException e) { return unauthorized(tenant, configId, "bad timestamp"); }
        if (Math.abs(Instant.now().getEpochSecond() - t) > 300) return unauthorized(tenant, configId, "timestamp outside replay window");
        String expect = Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), ts + "." + body);
        if (!Hashing.equalsConstantTime(expect, sig.substring(3))) return unauthorized(tenant, configId, "signature mismatch");
        if (seen.putIfAbsent(sig, t) != null) return unauthorized(tenant, configId, "signature replay");
        seen.entrySet().removeIf(e -> e.getValue() < Instant.now().getEpochSecond() - 600);
        JsonNode j;
        try { j = Json.parse(body); } catch (RuntimeException e) { return ResponseEntity.badRequest().body(Map.of("error", "invalid JSON")); }
        if ("ping".equals(j.path("type").asString(""))) return ResponseEntity.status(202).body(Map.of("pong", true));
        PipelineResult r = Current.asSystem(tenant, "webhook", () -> pipeline.process(new Envelope(tenant, null, configId, "P6", body, "LIVE", null, null, req.getRemoteAddr(), Instant.now())));
        return ResponseEntity.status(202).body(r.summary());
    }

    private ResponseEntity<Object> unauthorized(String tenant, String configId, String reason) {
        audit.record(tenant.matches("[a-z0-9-]{3,41}") ? tenant : DocumentStore.GLOBAL, "anonymous", "webhook.refused", "config/" + configId.replaceAll("[^A-Za-z0-9-]", ""), Map.of("reason", reason));
        return ResponseEntity.status(401).body(Map.of("error", "unauthorized"));
    }

    /** Built-in reference host receiver (EP-4 consumer in the demo host product): verifies the IntiMed signature. */
    @PostMapping("/api/webhooks/_host/{tenant}")
    public ResponseEntity<Object> hostReceiver(@PathVariable String tenant, @RequestBody String body, @RequestHeader(value = "X-IntiMed-Signature", required = false) String sig,
                                               @RequestHeader(value = "X-IntiMed-Timestamp", required = false) String ts, @RequestHeader(value = "X-IntiMed-Subscription", required = false) String sub) {
        ObjectNode s = sub == null ? null : store.get(tenant, "webhook-sub", sub).orElse(null);
        String secret = s == null ? null : secrets.get(tenant, "webhook-sub-" + sub).orElse(null);
        boolean valid = secret != null && sig != null && ts != null && Hashing.equalsConstantTime("v1=" + Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), ts + "." + body), sig);
        store.put(tenant, "host-received", Hashing.id("hr"), Json.obj().put("at", Instant.now().toString()).put("subscription", sub).put("signatureValid", valid)
                .set("event", Json.parse(body)), valid ? "VALID" : "INVALID", sub);
        return ResponseEntity.status(valid ? 200 : 401).body(Map.of("received", valid));
    }

    public List<ObjectNode> hostInbox(String tenant) { return store.list(tenant, "host-received"); }
}
