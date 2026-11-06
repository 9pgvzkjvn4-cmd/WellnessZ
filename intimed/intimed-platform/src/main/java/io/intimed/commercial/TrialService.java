package io.intimed.commercial;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Trial tenants (IM-E09-BL-001): a TRIAL tenant with an expiry date, simulator-only mode and a pre-provisioned sandbox
 * (one synthetic sandbox subject plus the list of simulator golden sessions of the signed drivers it can try). A daily
 * job suspends expired trials and offboards them after a grace period through {@link TenantService#offboard} – the
 * tenant DEK is crypto-shredded while the hash-chained audit evidence is retained.
 */
@Service
public class TrialService {
    public static final int MAX_DAYS = 90;
    public static final int DEFAULT_GRACE_DAYS = 14;
    public static final String SANDBOX_SUBJECT = "TRIAL-SANDBOX-001";
    private static final Logger LOG = LoggerFactory.getLogger(TrialService.class);
    private final TenantService tenants;
    private final DocumentStore store;
    private final DriverRegistry drivers;
    private final AuditService audit;
    private final boolean enabled;

    public TrialService(TenantService tenants, DocumentStore store, DriverRegistry drivers, AuditService audit,
                        @Value("${intimed.trials.enabled:true}") boolean enabled) {
        this.tenants = tenants;
        this.store = store;
        this.drivers = drivers;
        this.audit = audit;
        this.enabled = enabled;
    }

    public TenantService.Tenant create(String id, String name, String residency, int days, Integer graceDays, String actor) {
        if (days < 1 || days > MAX_DAYS) throw ApiException.bad("invalid-trial-length", "trial length must be 1–" + MAX_DAYS + " days");
        int grace = graceDays == null ? DEFAULT_GRACE_DAYS : graceDays;
        if (grace < 0 || grace > 60) throw ApiException.bad("invalid-grace", "grace period must be 0–60 days");
        if (name == null || name.isBlank() || name.length() > 200) throw ApiException.bad("invalid-name", "name is required (≤ 200 characters)");
        ObjectNode s = TenantService.defaults();
        Instant expires = Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        s.putObject("trial").put("expiresAt", expires.toString()).put("graceDays", grace).put("status", "ACTIVE").put("createdBy", actor);
        s.put("simulatorOnly", true).put("knowledgeBaseOptIn", false).put("benchmarkOptIn", false);
        s.set("entitlements", Json.arr().add("ENGINE").add("CATALOGUE").add("CONSOLE").add("INTELLIGENCE").add("HARNESS").add("TEMPLATES"));
        s.set("quotas", Json.obj().put("messagesPerSecond", 20).put("connectedDevices", 25).put("burst", 50));
        TenantService.Tenant t = tenants.create(id, name, residency == null ? "eu-west" : residency, "TRIAL", s, actor);
        seedSandbox(id, actor);
        audit.record(id, actor, "trial.create", "tenant/" + id, Map.of("expiresAt", expires.toString(), "graceDays", grace));
        return t;
    }

    private void seedSandbox(String tenant, String actor) {
        store.put(tenant, "subject", SANDBOX_SUBJECT, Json.obj().put("id", SANDBOX_SUBJECT).put("mrn", SANDBOX_SUBJECT).put("name", "Sandbox Subject (synthetic)")
                .put("badge", "").put("sandbox", true).put("birthDate", "1980-01-01").put("sex", "female").set("vendorIds", Json.arr()), "ACTIVE", null);
        ObjectNode sb = Json.obj().put("id", "sandbox").put("subject", SANDBOX_SUBJECT).put("mode", "SIMULATOR").put("createdAt", Instant.now().toString());
        ArrayNode sims = sb.putArray("simulators");
        for (DriverPackage d : drivers.all(tenant)) {
            if (d.tests().isMissingNode() || sims.size() >= 25) continue;
            sims.addObject().put("driver", d.ref()).put("name", d.name()).put("goldenSession", true);
        }
        store.put(tenant, "trial-sandbox", "sandbox", sb, "READY", null);
        audit.record(tenant, actor, "trial.sandbox.seed", "tenant/" + tenant, Map.of("simulators", sims.size()));
    }

    public List<ObjectNode> list() {
        List<ObjectNode> out = new ArrayList<>();
        for (TenantService.Tenant t : tenants.all()) {
            if (!t.settings().path("trial").isObject()) continue;
            out.add(Json.obj().put("id", t.id()).put("name", t.name()).put("status", t.status()).put("plan", t.plan())
                    .put("expiresAt", t.settings().at("/trial/expiresAt").asString()).put("graceDays", t.settings().at("/trial/graceDays").asInt())
                    .put("simulatorOnly", t.settings().path("simulatorOnly").asBoolean(false)));
        }
        return out;
    }

    public TenantService.Tenant extend(String id, int days, String actor) {
        if (days < 1 || days > MAX_DAYS) throw ApiException.bad("invalid-trial-length", "extension must be 1–" + MAX_DAYS + " days");
        TenantService.Tenant t = tenants.find(id);
        if (t == null || !t.settings().path("trial").isObject()) throw ApiException.notFound("trial tenant");
        Instant base = Instant.parse(t.settings().at("/trial/expiresAt").asString());
        if (base.isBefore(Instant.now())) base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ObjectNode patch = Json.obj();
        ObjectNode trial = (ObjectNode) t.settings().get("trial").deepCopy();
        trial.put("expiresAt", base.plus(days, ChronoUnit.DAYS).toString()).put("status", "ACTIVE");
        patch.set("trial", trial);
        TenantService.Tenant r = tenants.setStatus(id, "ACTIVE", patch, actor);
        audit.record(id, actor, "trial.extend", "tenant/" + id, Map.of("days", days, "expiresAt", trial.get("expiresAt").asString()));
        return r;
    }

    @Scheduled(cron = "${intimed.trials.cron:0 17 3 * * *}")
    public void daily() {
        if (!enabled) return;
        try { sweep(Instant.now()); } catch (RuntimeException e) { LOG.warn("trial sweep failed: {}", e.getClass().getSimpleName()); }
    }

    /** Suspends expired trials and offboards those past the grace period; returns the actions taken. */
    public List<ObjectNode> sweep(Instant now) {
        List<ObjectNode> actions = new ArrayList<>();
        for (TenantService.Tenant t : tenants.all()) {
            if (!t.settings().path("trial").isObject() || "OFFBOARDED".equals(t.status())) continue;
            Instant expires = Instant.parse(t.settings().at("/trial/expiresAt").asString());
            Instant offboardAt = expires.plus(t.settings().at("/trial/graceDays").asInt(DEFAULT_GRACE_DAYS), ChronoUnit.DAYS);
            if (now.isAfter(offboardAt)) {
                Map<String, Object> r = tenants.offboard(t.id(), "system:trials");
                actions.add(Json.obj().put("tenant", t.id()).put("action", "OFFBOARDED").put("documentsDeleted", ((Number) r.get("documentsDeleted")).longValue()));
            } else if (now.isAfter(expires) && "ACTIVE".equals(t.status())) {
                ObjectNode trial = (ObjectNode) t.settings().get("trial").deepCopy();
                trial.put("status", "SUSPENDED").put("suspendedAt", now.toString()).put("offboardAt", offboardAt.toString());
                tenants.setStatus(t.id(), "SUSPENDED", Json.obj().set("trial", trial), "system:trials");
                audit.record(t.id(), "system:trials", "trial.suspend", "tenant/" + t.id(), Map.of("expiresAt", expires.toString(), "offboardAt", offboardAt.toString()));
                actions.add(Json.obj().put("tenant", t.id()).put("action", "SUSPENDED").put("offboardAt", offboardAt.toString()));
            }
        }
        return actions;
    }
}
