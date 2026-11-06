package io.intimed.web;

import io.intimed.api.PublicMachineController;
import io.intimed.api.WebhookService;
import io.intimed.catalogue.CatalogueService;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.engine.transport.DeviceListeners;
import io.intimed.events.EventBus;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.TokenService;
import io.intimed.security.auth.UserService;
import io.intimed.store.DocumentStore;
import io.intimed.templates.TemplateRegistry;
import io.intimed.tenancy.TenantService;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Overview, integration (API clients, webhooks, events – IM-E07), tenants, metering and users (IM-E09 local subset). */
@RestController
@RequestMapping("/api")
public class PlatformController {
    private final DocumentStore store;
    private final CatalogueService catalogue;
    private final DriverRegistry drivers;
    private final TemplateRegistry templates;
    private final TenantService tenants;
    private final UserService users;
    private final TokenService tokens;
    private final WebhookService webhooks;
    private final EventBus events;
    private final PublicMachineController machine;
    private final DeviceListeners listeners;
    private final ObjectProvider<BuildProperties> build;

    public PlatformController(DocumentStore store, CatalogueService catalogue, DriverRegistry drivers, TemplateRegistry templates, TenantService tenants, UserService users,
                              TokenService tokens, WebhookService webhooks, EventBus events, PublicMachineController machine, DeviceListeners listeners, ObjectProvider<BuildProperties> build) {
        this.store = store;
        this.catalogue = catalogue;
        this.drivers = drivers;
        this.templates = templates;
        this.tenants = tenants;
        this.users = users;
        this.tokens = tokens;
        this.webhooks = webhooks;
        this.events = events;
        this.machine = machine;
        this.listeners = listeners;
        this.build = build;
    }

    private static String t() { return Current.tenant(); }

    @GetMapping("/public/info")
    public Map<String, Object> info() {
        BuildProperties b = build.getIfAvailable();
        return Map.of("product", "IntiMed", "version", b == null ? "2.1.0" : b.getVersion(), "environment", System.getenv().getOrDefault("INTIMED_ENVIRONMENT", "LOCAL"), "mllpPort", listeners.mllpPort(), "ingressPort", listeners.ingressPort());
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        String tn = t();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("catalogue", catalogue.stats());
        m.put("drivers", drivers.all(tn).size());
        m.put("templates", templates.all(tn).size());
        Map<String, Long> cs = new LinkedHashMap<>();
        for (ObjectNode c : store.list(tn, "config")) cs.merge(c.get("status").asString(), 1L, Long::sum);
        m.put("configurations", cs);
        m.put("runs", store.count(tn, "run"));
        m.put("runsPassed", store.countByStatus(tn, "run", "PASSED"));
        m.put("observationsFiled", store.countByStatus(tn, "observation", "FILED"));
        m.put("held", store.countByStatus(tn, "observation", "HELD_VERIFY") + store.countByStatus(tn, "observation", "HELD_QUALITY"));
        m.put("unmatchedOpen", store.countByStatus(tn, "unmatched", "OPEN"));
        m.put("dlqOpen", store.countByStatus(tn, "dlq", "OPEN"));
        m.put("alertsOpen", store.countByStatus(tn, "security-alert", "OPEN"));
        m.put("endpoints", store.count(tn, "endpoint"));
        m.put("certifications", store.count(tn, "certification"));
        m.put("events", events.recent(tn, 12));
        return m;
    }

    // ---------------------------------------------------------------- integration
    @GetMapping("/integration/clients") public List<ObjectNode> clients() { Current.require(Role.INTEGRATION_DEV, Role.PARTNER_DEV, Role.TENANT_ADMIN, Role.DEVICE_ADMIN); return tokens.clients(t()); }
    @PostMapping("/integration/clients")
    public TokenService.ClientCreated createClient(@RequestBody JsonNode b) {
        Current.require(Role.INTEGRATION_DEV, Role.PARTNER_DEV, Role.TENANT_ADMIN);
        List<String> scopes = new ArrayList<>();
        b.path("scopes").forEach(s -> scopes.add(s.asString()));
        return tokens.createClient(t(), b.path("name").asString("client"), scopes, Current.actor());
    }
    @DeleteMapping("/integration/clients/{id}") public Map<String, String> revoke(@PathVariable String id) { Current.require(Role.INTEGRATION_DEV, Role.TENANT_ADMIN); tokens.revokeClient(t(), id, Current.actor()); return Map.of("revoked", id); }
    @GetMapping("/integration/scopes") public Object scopes() { return TokenService.SCOPES; }
    @GetMapping("/integration/webhooks") public List<ObjectNode> subs() { return webhooks.subscriptions(t()); }
    @PostMapping("/integration/webhooks")
    public ObjectNode subscribe(@RequestBody JsonNode b) {
        Current.require(Role.INTEGRATION_DEV, Role.PARTNER_DEV, Role.TENANT_ADMIN);
        List<String> ev = new ArrayList<>();
        b.path("events").forEach(e -> ev.add(e.asString()));
        return webhooks.create(t(), b.path("url").asString(), ev, Current.actor());
    }
    @DeleteMapping("/integration/webhooks/{id}") public Map<String, String> unsubscribe(@PathVariable String id) { webhooks.delete(t(), id, Current.actor()); return Map.of("deleted", id); }
    @GetMapping("/integration/deliveries") public List<ObjectNode> deliveries() { return webhooks.deliveries(t()); }
    @PostMapping("/integration/deliveries/{id}/replay") public ObjectNode replay(@PathVariable String id) { return webhooks.replay(t(), id, Current.actor()); }
    @GetMapping("/integration/events") public List<ObjectNode> eventList() { return events.recent(t(), 100); }
    @GetMapping("/integration/event-types") public List<String> types() { return EventBus.TYPES; }
    @GetMapping("/integration/host-inbox") public List<ObjectNode> inbox() { return machine.hostInbox(t()).reversed(); }

    // ---------------------------------------------------------------- tenants & users & metering
    @GetMapping("/tenants")
    public List<Map<String, Object>> tenantList() {
        Current.require(Role.PLATFORM_SRE, Role.TENANT_ADMIN);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TenantService.Tenant tt : tenants.all()) {
            if (!Current.principal().has(Role.PLATFORM_SRE) && !tt.id().equals(t())) continue;
            out.add(Map.of("id", tt.id(), "name", tt.name(), "residency", tt.residency(), "plan", tt.plan(), "status", tt.status()));
        }
        return out;
    }
    @PostMapping("/tenants")
    public TenantService.Tenant createTenant(@RequestBody JsonNode b) {
        Current.require(Role.PLATFORM_SRE);
        return tenants.create(b.path("id").asString(), b.path("name").asString(), b.path("residency").asString("eu-west"), b.path("plan").asString("TRIAL"), null, Current.actor());
    }
    @GetMapping("/tenant") public TenantService.Tenant current() { return tenants.require(t()); }
    @PutMapping("/tenant/settings")
    public TenantService.Tenant settings(@RequestBody ObjectNode b) {
        Current.require(Role.TENANT_ADMIN);
        if (b.has("entitlements") || b.has("quotas")) throw ApiException.forbidden("commercial", "entitlements and quotas are managed by the platform operator");
        tenants.updateSettings(t(), b, Current.actor());
        return tenants.require(t());
    }
    @GetMapping("/tenant/metering") public List<Map<String, Object>> metering() { Current.require(Role.TENANT_ADMIN, Role.PLATFORM_SRE); return tenants.metering(t()); }
    @GetMapping("/tenant/users") public List<Map<String, Object>> users() { Current.require(Role.TENANT_ADMIN, Role.SECURITY_ANALYST); return users.list(t()); }
    @GetMapping("/tenant/analytics")
    public Map<String, Object> analytics() {
        Current.require(Role.TENANT_ADMIN, Role.DEVICE_ADMIN, Role.PLATFORM_SRE);
        String tn = t();
        long filed = store.countByStatus(tn, "observation", "FILED");
        long total = store.count(tn, "observation");
        long verified = store.list(tn, "observation").stream().filter(o -> o.has("verifiedBy")).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("observations", total);
        m.put("captureRatePct", total == 0 ? 0 : Math.round(filed * 1000.0 / total) / 10.0);
        m.put("verifiedByClinician", verified);
        m.put("estimatedMinutesSaved", Math.round(filed * 1.5));
        m.put("calibrationCompliancePct", calibrationCompliance(tn));
        m.put("note", "De-identified operational counts; time saved uses the pack's planning assumption of ≥ 1 min (acute) to ≥ 3 min (OH) per reading");
        return m;
    }

    private double calibrationCompliance(String tn) {
        List<ObjectNode> pub = store.listByStatus(tn, "config", "PUBLISHED");
        if (pub.isEmpty()) return 100;
        long ok = pub.stream().filter(c -> !"LOCKED".equals(c.at("/calibration/status").asString())).count();
        return Math.round(ok * 1000.0 / pub.size()) / 10.0;
    }

    @PostMapping("/tenants/{id}/offboard")
    public Map<String, Object> offboard(@PathVariable String id) { Current.require(Role.PLATFORM_SRE); return tenants.offboard(id, Current.actor()); }
}
