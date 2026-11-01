package io.intimed.web;

import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.security.endpoint.AnomalyService;
import io.intimed.security.endpoint.EndpointService;
import io.intimed.security.pki.LocalPki;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Security console (IM-E06): endpoints and PKI, anomalies and isolation, audit verification, secrets, scanners. */
@RestController
@RequestMapping("/api/security")
public class SecurityController {
    private final EndpointService endpoints;
    private final AnomalyService anomalies;
    private final AuditService audit;
    private final SecretStore secrets;
    private final DocumentStore store;

    public SecurityController(EndpointService endpoints, AnomalyService anomalies, AuditService audit, SecretStore secrets, DocumentStore store) {
        this.endpoints = endpoints;
        this.anomalies = anomalies;
        this.audit = audit;
        this.secrets = secrets;
        this.store = store;
    }

    private static String t() { return Current.tenant(); }
    private static void sec() { Current.require(Role.SECURITY_ANALYST, Role.DEVICE_ADMIN, Role.CISO_DPO, Role.PLATFORM_SRE); }

    @GetMapping("/endpoints") public List<ObjectNode> endpoints() { sec(); return endpoints.list(t()); }
    @PostMapping("/endpoints")
    public Map<String, Object> register(@RequestBody JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.SECURITY_ANALYST);
        EndpointService.Registered r = endpoints.register(t(), b.path("name").asString(), b.path("type").asString("GATEWAY"), b.path("site").asString("Main clinic"), b.path("zone").asString("MDZ-1"), Current.actor());
        return Map.of("endpoint", r.endpoint(), "enrolmentToken", r.enrolmentToken(), "expiresInMinutes", 15, "note", "single use – shown once");
    }
    @PostMapping("/endpoints/{id}/rotation-token") public Map<String, Object> rotate(@PathVariable String id) { Current.require(Role.DEVICE_ADMIN, Role.SECURITY_ANALYST); return Map.of("enrolmentToken", endpoints.rotationToken(t(), id, Current.actor())); }
    @PostMapping("/endpoints/{id}/revoke") public ObjectNode revoke(@PathVariable String id, @RequestBody(required = false) JsonNode b) {
        Current.require(Role.DEVICE_ADMIN, Role.SECURITY_ANALYST);
        return endpoints.setStatus(t(), id, "REVOKED", b == null ? "revoked" : b.path("reason").asString("revoked"), Current.actor());
    }
    @PostMapping("/endpoints/{id}/release") public ObjectNode releaseEndpoint(@PathVariable String id) { Current.require(Role.SECURITY_ANALYST); return anomalies.release(t(), id, Current.actor()); }
    @GetMapping("/endpoints/expiring") public List<ObjectNode> expiring(@RequestParam(defaultValue = "30") int days) { sec(); return endpoints.expiring(t(), days); }
    @GetMapping("/pki/root") public Map<String, String> root() { return Map.of("rootCa", LocalPki.toPem(LocalPki.get().root()), "fingerprint", LocalPki.fingerprint(LocalPki.get().root())); }

    @GetMapping("/alerts")
    public List<ObjectNode> alerts() {
        sec();
        List<ObjectNode> out = new ArrayList<>(anomalies.alerts(t()));
        if (Current.principal().hasAny(Role.SECURITY_ANALYST, Role.PLATFORM_SRE)) for (ObjectNode g : anomalies.alerts(DocumentStore.GLOBAL)) out.add(g.deepCopy().put("scope", "INSTALLATION"));
        return out;
    }
    @PostMapping("/alerts/{id}/isolate") public ObjectNode isolate(@PathVariable String id) { Current.require(Role.SECURITY_ANALYST); return anomalies.isolate(t(), id, Current.actor()); }
    @GetMapping("/anomaly-rules") public Object rules() { return AnomalyService.RULES; }

    @GetMapping("/audit") public List<Map<String, Object>> audit(@RequestParam(defaultValue = "100") int limit) { Current.require(Role.SECURITY_ANALYST, Role.CISO_DPO, Role.AUDITOR, Role.TENANT_ADMIN); return audit.recent(t(), Math.min(limit, 500)); }
    @GetMapping("/audit/verify") public Map<String, Object> verify() { Current.require(Role.SECURITY_ANALYST, Role.CISO_DPO, Role.AUDITOR); return audit.verify(); }

    @GetMapping("/secrets") public Map<String, Object> names() { Current.require(Role.SECURITY_ANALYST, Role.DEVICE_ADMIN, Role.TENANT_ADMIN); return Map.of("provider", secrets.provider(), "names", secrets.names(t())); }
    @PutMapping("/secrets/{name}") public Map<String, String> put(@PathVariable String name, @RequestBody JsonNode b) {
        Current.require(Role.SECURITY_ANALYST, Role.DEVICE_ADMIN, Role.TENANT_ADMIN);
        secrets.put(t(), name, b.path("value").asString(), Current.actor());
        return Map.of("stored", "secret://" + name);
    }
    @DeleteMapping("/secrets/{name}") public Map<String, String> del(@PathVariable String name) { Current.require(Role.SECURITY_ANALYST, Role.TENANT_ADMIN); secrets.delete(t(), name, Current.actor()); return Map.of("deleted", name); }
    @PostMapping("/scan/secrets") public Object scan(@RequestBody String text) { return Map.of("findings", SecretScanner.scan(text)); }
    @GetMapping("/posture") public List<ObjectNode> posture() { sec(); return store.list(t(), "posture"); }
    @GetMapping("/events") public List<ObjectNode> events() { sec(); return store.list(t(), "event").reversed().stream().limit(200).toList(); }
    @GetMapping("/drill")
    public Map<String, Object> drill() {
        sec();
        List<ObjectNode> iso = anomalies.alerts(t()).stream().filter(a -> a.has("timeToIsolateSeconds")).toList();
        return Map.of("isolations", iso.size(), "withinTarget", iso.stream().filter(a -> a.path("withinTarget").asBoolean()).count(), "targetSeconds", 900,
                "alerts", Json.tree(iso));
    }
}
