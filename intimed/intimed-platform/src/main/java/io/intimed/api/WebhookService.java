package io.intimed.api;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.security.pki.LocalPki;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Signed webhooks (IM-E07-F03 / IM-E07-BL-003, EP-4): per-subscription secret in the vault, HMAC-SHA256 over
 * {@code timestamp.body}, retries with exponential back-off (1, 4, 16 min), delivery log, manual replay. Egress is
 * restricted to an allow-list of hosts (SSRF guard) and HTTPS only.
 */
@Service
public class WebhookService {
    private final DocumentStore store;
    private final SecretStore secrets;
    private final AuditService audit;
    private final Set<String> allowedHosts;
    private final HttpClient http;

    public WebhookService(DocumentStore store, SecretStore secrets, AuditService audit, EventBus bus,
                          @Value("${intimed.webhooks.allowed-hosts:localhost,127.0.0.1}") String allowed) {
        this.store = store;
        this.secrets = secrets;
        this.audit = audit;
        this.allowedHosts = Arrays.stream(allowed.split(",")).map(String::trim).collect(Collectors.toSet());
        SSLContext trust = LocalPki.clientSslContext(LocalPki.get().root(), (java.security.cert.X509Certificate[]) null, null);
        this.http = HttpClient.newBuilder().sslContext(trust).connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
        bus.subscribe(this::onEvent);
    }

    public ObjectNode create(String tenant, String url, List<String> events, String actor) {
        URI u;
        try { u = URI.create(url); } catch (IllegalArgumentException e) { throw ApiException.bad("invalid-url", "invalid URL"); }
        if (!"https".equals(u.getScheme())) throw ApiException.bad("https-required", "webhook URLs must use HTTPS");
        if (!allowedHosts.contains(u.getHost())) throw ApiException.bad("host-not-allowed", "host " + u.getHost() + " is not on the egress allow-list (intimed.webhooks.allowed-hosts)");
        for (String e : events) if (!EventBus.TYPES.contains(e)) throw ApiException.bad("invalid-event", "unknown event " + e);
        String id = "whs-" + Hashing.randomToken(6).toLowerCase().replaceAll("[^a-z0-9]", "0");
        String secret = Hashing.randomToken(32);
        secrets.put(tenant, "webhook-sub-" + id, secret, actor);
        ObjectNode s = Json.obj().put("id", id).put("url", url).put("createdBy", actor).put("createdAt", Instant.now().toString()).put("status", "ACTIVE")
                .put("secretRef", "secret://webhook-sub-" + id);
        s.set("events", Json.tree(events));
        store.put(tenant, "webhook-sub", id, s, "ACTIVE", null);
        audit.record(tenant, actor, "webhook.subscribe", "webhook-sub/" + id, Map.of("events", events, "host", u.getHost()));
        ObjectNode out = (ObjectNode) s.deepCopy();
        out.put("signingSecret", secret).put("note", "shown once – store it in your host product's vault");
        return out;
    }

    public List<ObjectNode> subscriptions(String tenant) { return store.list(tenant, "webhook-sub"); }

    public void delete(String tenant, String id, String actor) {
        store.delete(tenant, "webhook-sub", id);
        secrets.delete(tenant, "webhook-sub-" + id, actor);
    }

    public List<ObjectNode> deliveries(String tenant) { return store.list(tenant, "webhook-delivery").reversed(); }

    void onEvent(String tenant, ObjectNode event) {
        if (DocumentStore.GLOBAL.equals(tenant)) return;
        for (ObjectNode s : store.listByStatus(tenant, "webhook-sub", "ACTIVE")) {
            boolean match = false;
            for (JsonNode e : s.path("events")) match |= e.asString().equals(event.get("type").asString());
            if (!match) continue;
            String id = Hashing.id("whd");
            ObjectNode d = Json.obj().put("id", id).put("subscription", s.get("id").asString()).put("event", event.get("id").asString()).put("type", event.get("type").asString())
                    .put("attempts", 0).put("status", "PENDING").put("nextAt", Instant.now().toString());
            d.set("body", event);
            store.put(tenant, "webhook-delivery", id, d, "PENDING", s.get("id").asString());
            Thread.ofVirtual().start(() -> attempt(tenant, id));
        }
    }

    void attempt(String tenant, String id) {
        ObjectNode d = store.get(tenant, "webhook-delivery", id).orElse(null);
        if (d == null) return;
        ObjectNode s = store.get(tenant, "webhook-sub", d.get("subscription").asString()).orElse(null);
        if (s == null) return;
        String secret = secrets.get(tenant, "webhook-sub-" + s.get("id").asString()).orElse("");
        String body = Json.write(d.get("body"));
        String ts = String.valueOf(Instant.now().getEpochSecond());
        int code;
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(s.get("url").asString())).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").header("X-IntiMed-Timestamp", ts).header("X-IntiMed-Subscription", s.get("id").asString())
                    .header("X-IntiMed-Signature", "v1=" + Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), ts + "." + body))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            code = r.statusCode();
        } catch (Exception e) {
            code = -1;
        }
        int attempts = d.path("attempts").asInt() + 1;
        d.put("attempts", attempts).put("lastStatus", code).put("lastAttemptAt", Instant.now().toString());
        if (code >= 200 && code < 300) d.put("status", "DELIVERED");
        else if (attempts >= 4) d.put("status", "FAILED");
        else d.put("status", "RETRY").put("nextAt", Instant.now().plusSeconds((long) Math.pow(4, attempts - 1) * 60).toString());
        store.put(tenant, "webhook-delivery", id, d, d.get("status").asString(), s.get("id").asString());
    }

    @Scheduled(fixedDelay = 60_000)
    public void retry() {
        for (String tenant : store.list(DocumentStore.GLOBAL, "tenant-index").stream().map(t -> t.get("id").asString()).toList()) {
            for (ObjectNode d : store.listByStatus(tenant, "webhook-delivery", "RETRY")) {
                if (Instant.parse(d.get("nextAt").asString()).isBefore(Instant.now())) attempt(tenant, d.get("id").asString());
            }
        }
    }

    public ObjectNode replay(String tenant, String id, String actor) {
        ObjectNode d = store.require(tenant, "webhook-delivery", id);
        d.put("attempts", 0).put("status", "PENDING");
        store.put(tenant, "webhook-delivery", id, d, "PENDING", d.get("subscription").asString());
        audit.record(tenant, actor, "webhook.replay", "webhook-delivery/" + id, null);
        attempt(tenant, id);
        return store.require(tenant, "webhook-delivery", id);
    }
}
