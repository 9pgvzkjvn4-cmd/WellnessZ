package io.intimed.security.endpoint;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anomaly detection and compromise playbook (SC-09, IM-E06-F08 / IM-E06-BL-008, workflow F-IM-06): sliding-window
 * rules per endpoint raise alerts; the SOC isolates with one click (or automatically for critical rules); isolated
 * endpoints are refused at the TLS layer; release requires re-certification – a passing exchange test of every bound
 * configuration after the isolation time. Drill metrics record detection → isolation (target &lt; 15 min).
 */
@Service
public class AnomalyService {
    public record Rule(String id, String name, int threshold, Duration window, String severity, boolean autoIsolate) { }

    public static final Map<String, Rule> RULES = Map.of(
            "UNKNOWN_IDENTITY", new Rule("AN-01", "Unknown, revoked or isolated certificate attempted a connection", 1, Duration.ofMinutes(5), "HIGH", false),
            "PLAINTEXT_ATTEMPT", new Rule("AN-02", "Plaintext or non-mTLS connection attempt on a device port", 3, Duration.ofMinutes(5), "MEDIUM", false),
            "RATE_EXCEEDED", new Rule("AN-03", "Message rate above connector quota", 1, Duration.ofMinutes(1), "MEDIUM", false),
            "PARSE_ERROR_BURST", new Rule("AN-04", "Burst of malformed messages (possible fuzzing)", 5, Duration.ofMinutes(1), "HIGH", false),
            "NEW_DEVICE_SERIAL", new Rule("AN-05", "Results from a device serial not bound to the configuration", 1, Duration.ofMinutes(10), "HIGH", false),
            "DUPLICATE_BURST", new Rule("AN-06", "Replay pattern – repeated identical messages", 10, Duration.ofMinutes(1), "MEDIUM", false),
            "VALUE_BURST", new Rule("AN-07", "Spot-check device posting at streaming rate (fabricated results)", 30, Duration.ofMinutes(1), "CRITICAL", true));

    private final DocumentStore store;
    private final AuditService audit;
    private final EndpointService endpoints;
    private final EventBus events;
    private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastAlert = new ConcurrentHashMap<>();

    public AnomalyService(DocumentStore store, AuditService audit, EndpointService endpoints, EventBus events) {
        this.store = store;
        this.audit = audit;
        this.endpoints = endpoints;
        this.events = events;
    }

    /** Records a signal; returns the alert if a rule fired. */
    public ObjectNode signal(String tenant, String endpointId, String type, String detail) {
        Rule r = RULES.get(type);
        if (r == null) throw new IllegalArgumentException("unknown signal " + type);
        String key = tenant + "/" + endpointId + "/" + type;
        Deque<Instant> w = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant now = Instant.now();
        synchronized (w) {
            w.addLast(now);
            while (!w.isEmpty() && w.peekFirst().isBefore(now.minus(r.window()))) w.removeFirst();
            if (w.size() < r.threshold()) return null;
            w.clear();
        }
        Instant la = lastAlert.get(key);
        if (la != null && la.isAfter(now.minus(r.window()))) return null;
        lastAlert.put(key, now);
        String id = Hashing.id("alr");
        ObjectNode a = Json.obj().put("id", id).put("rule", r.id()).put("signal", type).put("name", r.name()).put("severity", r.severity())
                .put("endpoint", endpointId).put("detail", detail).put("detectedAt", now.toString()).put("status", "OPEN");
        store.put(tenant, "security-alert", id, a, "OPEN", endpointId);
        audit.record(tenant, "system:anomaly", "security.alert", "endpoint/" + endpointId, Map.of("rule", r.id(), "severity", r.severity()));
        events.publish(tenant, "device.alert", Json.obj().put("alert", id).put("rule", r.id()).put("endpoint", endpointId).put("severity", r.severity()));
        if (r.autoIsolate() && endpointId != null && !endpointId.startsWith("unknown")) isolate(tenant, id, "system:anomaly-auto");
        return a;
    }

    public ObjectNode isolate(String tenant, String alertId, String actor) {
        ObjectNode a = store.require(tenant, "security-alert", alertId);
        String ep = a.get("endpoint").asString();
        ObjectNode e = endpoints.setStatus(tenant, ep, "ISOLATED", "alert " + alertId, actor);
        Instant now = Instant.now();
        long secs = Duration.between(Instant.parse(a.get("detectedAt").asString()), now).toSeconds();
        a.put("status", "ISOLATED").put("isolatedAt", now.toString()).put("isolatedBy", actor).put("timeToIsolateSeconds", secs)
                .put("withinTarget", secs <= 900);
        store.put(tenant, "security-alert", alertId, a, "ISOLATED", ep);
        for (JsonNode c : e.withArray("configs")) {
            store.get(tenant, "config", c.asString()).ifPresent(cfg -> {
                cfg.put("recertificationRequired", true).put("recertificationSince", now.toString());
                store.put(tenant, "config", c.asString(), cfg, cfg.get("status").asString(), cfg.path("ref").asString(null));
            });
        }
        return a;
    }

    /** Release gate: every configuration bound to the endpoint must have a passing exchange test after isolation. */
    public ObjectNode release(String tenant, String endpointId, String actor) {
        ObjectNode e = endpoints.require(tenant, endpointId);
        if (!"ISOLATED".equals(e.get("status").asString())) throw ApiException.conflict("not-isolated", "endpoint is not isolated");
        Instant since = Instant.parse(e.path("statusChangedAt").asString(Instant.EPOCH.toString()));
        for (JsonNode c : e.withArray("configs")) {
            ObjectNode cfg = store.require(tenant, "config", c.asString());
            String passed = cfg.path("lastPassingRunAt").asString(null);
            if (passed == null || Instant.parse(passed).isBefore(since)) {
                audit.record(tenant, actor, "endpoint.release.refused", "endpoint/" + endpointId, Map.of("config", c.asString()));
                throw ApiException.conflict("recertification-required", "configuration " + c.asString() + " needs a passing exchange test after isolation");
            }
            cfg.put("recertificationRequired", false);
            store.put(tenant, "config", c.asString(), cfg, cfg.get("status").asString(), cfg.path("ref").asString(null));
        }
        ObjectNode r = endpoints.setStatus(tenant, endpointId, "ACTIVE", "released after re-certification", actor);
        for (ObjectNode a : store.listByRef(tenant, "security-alert", endpointId)) {
            if ("ISOLATED".equals(a.get("status").asString())) { a.put("status", "RESOLVED").put("resolvedAt", Instant.now().toString()); store.put(tenant, "security-alert", a.get("id").asString(), a, "RESOLVED", endpointId); }
        }
        return r;
    }

    public List<ObjectNode> alerts(String tenant) { return store.list(tenant, "security-alert").reversed(); }

    public void resetWindows() { windows.clear(); lastAlert.clear(); }
}
