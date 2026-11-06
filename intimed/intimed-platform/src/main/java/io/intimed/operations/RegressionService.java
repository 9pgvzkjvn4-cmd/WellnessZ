package io.intimed.operations;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.events.EventBus;
import io.intimed.harness.HarnessService;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled site regression suites with trend reporting (IM-E04-BL-007). A nightly job (cron
 * {@code intimed.regression.cron}, default 02:30; disabled with {@code intimed.regression.enabled=false}) re-runs the
 * configuration-time exchange test for every PUBLISHED configuration of every active tenant as a system actor and stores
 * one {@code regression-run} document per configuration run (configId, runId, status, passed/total, p95 latency,
 * startedAt). A failing regression of a published configuration raises a {@code regression.failed} event (ids and counts
 * only – never PHI) and an audit record. Trends (pass rate, p95 latency) are computed per configuration.
 */
@Service
public class RegressionService {
    public static final String KIND = "regression-run";
    static final int MAX_CONFIGS_PER_TENANT = 200;
    static final int MAX_TREND_POINTS = 60;
    private static final Logger LOG = LoggerFactory.getLogger(RegressionService.class);
    private final TenantService tenants;
    private final DocumentStore store;
    private final HarnessService harness;
    private final AuditService audit;
    private final EventBus events;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public RegressionService(TenantService tenants, DocumentStore store, HarnessService harness, AuditService audit, EventBus events,
                             @Value("${intimed.regression.enabled:true}") boolean enabled) {
        this.tenants = tenants;
        this.store = store;
        this.harness = harness;
        this.audit = audit;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Nightly suite across all active tenants. Tests call {@link #runAllTenants(String)} directly. */
    @Scheduled(cron = "${intimed.regression.cron:0 30 2 * * *}")
    public void nightly() {
        if (!enabled) return;
        runAllTenants("SCHEDULED");
    }

    public Map<String, Object> runAllTenants(String trigger) {
        if (!running.compareAndSet(false, true)) throw ApiException.conflict("regression-running", "a regression suite is already running");
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            for (TenantService.Tenant t : tenants.all()) {
                if (!"ACTIVE".equals(t.status())) continue;
                try {
                    out.put(t.id(), Current.asSystem(t.id(), "system:regression", () -> runTenantInternal(t.id(), trigger, "system:regression")).size());
                } catch (RuntimeException e) {
                    LOG.warn("regression suite failed for a tenant: {}", e.getClass().getSimpleName());
                }
            }
            return out;
        } finally {
            running.set(false);
        }
    }

    /** Manual trigger for the caller's tenant (DEVICE_ADMIN / PLATFORM_SRE – checked by the controller). */
    public List<ObjectNode> runTenant(String tenant, String trigger, String requestedBy) {
        if (!running.compareAndSet(false, true)) throw ApiException.conflict("regression-running", "a regression suite is already running");
        try {
            audit.record(tenant, requestedBy, "regression.trigger", "tenant/" + tenant, Map.of("trigger", trigger));
            // the suite itself always runs as the system actor so evidence is attributable to the scheduler, not a person
            return Current.asSystem(tenant, "system:regression", () -> runTenantInternal(tenant, trigger, "system:regression"));
        } finally {
            running.set(false);
        }
    }

    private List<ObjectNode> runTenantInternal(String tenant, String trigger, String actor) {
        String batch = Hashing.id("rgb");
        List<ObjectNode> out = new ArrayList<>();
        List<ObjectNode> published = store.listByStatus(tenant, "config", "PUBLISHED");
        for (ObjectNode cfg : published.subList(0, Math.min(published.size(), MAX_CONFIGS_PER_TENANT))) {
            out.add(runConfig(tenant, cfg.get("id").asString(), batch, trigger, actor));
        }
        return out;
    }

    ObjectNode runConfig(String tenant, String configId, String batch, String trigger, String actor) {
        String id = Hashing.id("rg");
        Instant started = Instant.now();
        ObjectNode doc = Json.obj().put("id", id).put("configId", configId).put("batchId", batch).put("trigger", trigger)
                .put("startedAt", started.toString());
        try {
            ObjectNode run = harness.run(tenant, configId, actor);
            doc.put("runId", run.get("id").asString()).put("status", run.get("status").asString())
                    .put("passed", run.path("passed").asInt()).put("total", run.path("total").asInt())
                    .put("latencyP95Ms", run.path("latencyP95Ms").asLong());
            ArrayNode failed = doc.putArray("failedChecks");
            run.path("results").properties().forEach(e -> { if ("FAIL".equals(e.getValue().path("status").asString())) failed.add(e.getKey()); });
        } catch (RuntimeException e) {
            // configuration can no longer be exercised (driver withdrawn, bindings removed …) – a regression in itself
            doc.put("status", "ERROR").put("passed", 0).put("total", 0).put("error", e instanceof ApiException a ? a.code() : e.getClass().getSimpleName());
        }
        doc.put("finishedAt", Instant.now().toString()).put("durationMs", Instant.now().toEpochMilli() - started.toEpochMilli());
        store.put(tenant, KIND, id, doc, doc.get("status").asString(), configId);
        boolean ok = "PASSED".equals(doc.get("status").asString());
        audit.record(tenant, actor, ok ? "regression.passed" : "regression.failed", "config/" + configId,
                Map.of("regressionRun", id, "status", doc.get("status").asString(), "passed", doc.get("passed").asInt(), "total", doc.get("total").asInt()));
        if (!ok) {
            events.publish(tenant, "regression.failed", Json.obj().put("config", configId).put("regressionRun", id).put("run", doc.path("runId").asString(""))
                    .put("status", doc.get("status").asString()).put("passed", doc.get("passed").asInt()).put("total", doc.get("total").asInt()));
        }
        tenants.meter(tenant, "regression.runs", 1);
        return doc;
    }

    public List<ObjectNode> runs(String tenant, String configId) {
        List<ObjectNode> l = configId == null ? store.list(tenant, KIND) : store.listByRef(tenant, KIND, configId);
        return l.subList(Math.max(0, l.size() - 500), l.size());
    }

    /** Pass-rate and p95-latency trend per configuration (last {@value #MAX_TREND_POINTS} runs each). */
    public ObjectNode trends(String tenant, String configId) {
        Map<String, List<ObjectNode>> byConfig = new LinkedHashMap<>();
        for (ObjectNode r : configId == null ? store.list(tenant, KIND) : store.listByRef(tenant, KIND, configId)) {
            byConfig.computeIfAbsent(r.get("configId").asString(), k -> new ArrayList<>()).add(r);
        }
        ObjectNode out = Json.obj().put("generatedAt", Instant.now().toString()).put("enabled", enabled);
        ArrayNode configs = out.putArray("configs");
        long allRuns = 0, allPassed = 0;
        for (var e : byConfig.entrySet()) {
            List<ObjectNode> runs = new ArrayList<>(e.getValue());
            runs.sort(Comparator.comparing(r -> r.path("startedAt").asString()));
            List<ObjectNode> last = runs.subList(Math.max(0, runs.size() - MAX_TREND_POINTS), runs.size());
            ObjectNode c = configs.addObject().put("configId", e.getKey());
            store.get(tenant, "config", e.getKey()).ifPresent(cfg -> c.put("name", cfg.path("name").asString()).put("configStatus", cfg.path("status").asString()));
            ArrayNode pts = c.putArray("points");
            long passedRuns = 0;
            List<Long> p95s = new ArrayList<>();
            for (ObjectNode r : last) {
                int total = r.path("total").asInt();
                double rate = total == 0 ? 0 : r.path("passed").asInt() * 100.0 / total;
                ObjectNode p = pts.addObject().put("at", r.path("startedAt").asString()).put("status", r.path("status").asString())
                        .put("checkPassRatePct", round1(rate)).put("runId", r.path("runId").asString(""));
                if (r.has("latencyP95Ms")) { p.put("latencyP95Ms", r.get("latencyP95Ms").asLong()); p95s.add(r.get("latencyP95Ms").asLong()); }
                if ("PASSED".equals(r.path("status").asString())) passedRuns++;
            }
            allRuns += last.size();
            allPassed += passedRuns;
            c.put("runs", last.size()).put("passRatePct", last.isEmpty() ? 0 : round1(passedRuns * 100.0 / last.size()));
            if (!p95s.isEmpty()) {
                c.put("latestP95Ms", p95s.get(p95s.size() - 1)).put("meanP95Ms", Math.round(p95s.stream().mapToLong(Long::longValue).average().orElse(0)));
            }
            c.put("lastStatus", last.isEmpty() ? "NONE" : last.get(last.size() - 1).path("status").asString());
            c.put("trend", trend(last));
        }
        out.put("runs", allRuns).put("passRatePct", allRuns == 0 ? 0 : round1(allPassed * 100.0 / allRuns));
        return out;
    }

    /** IMPROVING / STABLE / DEGRADING: pass rate of the most recent half vs the older half. */
    static String trend(List<ObjectNode> runs) {
        if (runs.size() < 2) return "INSUFFICIENT_DATA";
        int mid = runs.size() / 2;
        double older = passRate(runs.subList(0, mid)), recent = passRate(runs.subList(mid, runs.size()));
        if (recent > older + 0.05) return "IMPROVING";
        if (recent < older - 0.05) return "DEGRADING";
        return "STABLE";
    }

    private static double passRate(List<ObjectNode> l) {
        return l.isEmpty() ? 0 : l.stream().filter(r -> "PASSED".equals(r.path("status").asString())).count() / (double) l.size();
    }

    /** Pass rate over the last {@code days} days (health score input); -1 when no regression ran. */
    public double passRate(String tenant, int days) {
        Instant since = Instant.now().minusSeconds(days * 86400L);
        List<ObjectNode> l = store.list(tenant, KIND).stream().filter(r -> Instant.parse(r.path("startedAt").asString("1970-01-01T00:00:00Z")).isAfter(since)).toList();
        return l.isEmpty() ? -1 : passRate(l);
    }

    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
}
