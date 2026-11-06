package io.intimed.commercial;

import io.intimed.common.Json;
import io.intimed.console.intelligence.KnowledgeBase;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Privacy-preserving cross-tenant benchmarking (IM-E02-BL-007 / IM-E09-BL-004). Tenants opt in through the
 * {@code benchmarkOptIn} setting (default off). For each dimension – device family, connectivity option, host template
 * – per-tenant metrics are computed inside each tenant's own partition (configurations, certified rate, mean harness
 * p95 latency, dead-letter rate) and only then pooled. A peer cell is released only when at least {@value #K}
 * distinct organisations contribute (k-anonymity); smaller cells are suppressed entirely (no counts, no values). The
 * output never contains tenant ids or names. Peers are shown only to tenants that contribute themselves (give-to-get);
 * released cells are also contributed as de-identified aggregates to the configuration knowledge base.
 */
@Service
public class BenchmarkService {
    public static final int K = 5;
    public static final List<String> DIMENSIONS = List.of("family", "option", "template");
    private static final Logger LOG = LoggerFactory.getLogger(BenchmarkService.class);
    private final TenantService tenants;
    private final DocumentStore store;
    private final KnowledgeBase kb;
    private final AuditService audit;
    private final boolean enabled;

    public BenchmarkService(TenantService tenants, DocumentStore store, KnowledgeBase kb, AuditService audit, @Value("${intimed.benchmarks.enabled:true}") boolean enabled) {
        this.tenants = tenants;
        this.store = store;
        this.kb = kb;
        this.audit = audit;
        this.enabled = enabled;
    }

    /** Per-tenant metric accumulator for one cell. */
    static final class M {
        long configs, certified, observations, dlq, latencySamples;
        double latencySum;

        void add(M o) { configs += o.configs; certified += o.certified; observations += o.observations; dlq += o.dlq; latencySamples += o.latencySamples; latencySum += o.latencySum; }
        double certifiedRate() { return configs == 0 ? 0 : certified / (double) configs; }
        double dlqRate() { return observations + dlq == 0 ? 0 : dlq / (double) (observations + dlq); }
        Double meanLatency() { return latencySamples == 0 ? null : latencySum / latencySamples; }
    }

    /** dimension → key → metrics of ONE tenant (computed inside that tenant's partition). */
    Map<String, Map<String, M>> tenantMetrics(String tenant) {
        Map<String, Map<String, M>> out = new LinkedHashMap<>();
        DIMENSIONS.forEach(d -> out.put(d, new TreeMap<>()));
        for (ObjectNode c : store.list(tenant, "config")) {
            String st = c.path("status").asString();
            if (List.of("DECOMMISSIONED", "SUPERSEDED").contains(st)) continue;
            String id = c.get("id").asString();
            M m = new M();
            m.configs = 1;
            m.certified = List.of("CERTIFIED", "PUBLISHED").contains(st) || c.hasNonNull("certificationId") ? 1 : 0;
            m.observations = store.countByRef(tenant, "observation", id);
            m.dlq = store.countByRef(tenant, "dlq", id);
            for (ObjectNode r : store.listByRef(tenant, "run", id)) {
                if (r.has("latencyP95Ms")) { m.latencySamples++; m.latencySum += r.get("latencyP95Ms").asDouble(); }
            }
            add(out.get("family"), c.path("family").asString(""), m);
            add(out.get("option"), c.path("option").asString(""), m);
            for (JsonNode b : c.path("bindings")) add(out.get("template"), b.path("templateId").asString(""), m);
        }
        return out;
    }

    private static void add(Map<String, M> map, String key, M m) {
        if (key == null || key.isBlank()) return;
        map.computeIfAbsent(key, k -> new M()).add(m);
    }

    private static boolean optedIn(TenantService.Tenant t) { return "ACTIVE".equals(t.status()) && t.settings().path("benchmarkOptIn").asBoolean(false); }

    /** Released (k-anonymous) peer cells: dimension → key → aggregate; suppressed cells map to null. */
    Map<String, Map<String, ObjectNode>> peerCells() {
        Map<String, Map<String, List<M>>> contributions = new LinkedHashMap<>();
        DIMENSIONS.forEach(d -> contributions.put(d, new TreeMap<>()));
        for (TenantService.Tenant t : tenants.all()) {
            if (!optedIn(t)) continue;
            tenantMetrics(t.id()).forEach((dim, cells) -> cells.forEach((key, m) -> contributions.get(dim).computeIfAbsent(key, k -> new ArrayList<>()).add(m)));
        }
        Map<String, Map<String, ObjectNode>> out = new LinkedHashMap<>();
        contributions.forEach((dim, cells) -> {
            Map<String, ObjectNode> o = new TreeMap<>();
            cells.forEach((key, list) -> o.put(key, list.size() < K ? null : aggregate(list)));
            out.put(dim, o);
        });
        return out;
    }

    static ObjectNode aggregate(List<M> perTenant) {
        M pooled = new M();
        perTenant.forEach(pooled::add);
        double meanConfigs = perTenant.stream().mapToLong(m -> m.configs).average().orElse(0);
        ObjectNode a = Json.obj().put("organisations", perTenant.size() >= 10 ? "10+" : K + "-9")
                .put("meanConfigsPerOrganisation", round(meanConfigs, 1))
                .put("certifiedRatePct", round(pooled.certifiedRate() * 100, 1))
                .put("dlqRatePct", round(pooled.dlqRate() * 100, 2));
        if (pooled.meanLatency() != null) a.put("meanHarnessP95Ms", Math.round(pooled.meanLatency()));
        return a;
    }

    static ObjectNode own(M m) {
        ObjectNode a = Json.obj().put("configs", m.configs).put("certifiedRatePct", round(m.certifiedRate() * 100, 1)).put("dlqRatePct", round(m.dlqRate() * 100, 2));
        if (m.meanLatency() != null) a.put("meanHarnessP95Ms", Math.round(m.meanLatency()));
        return a;
    }

    public ObjectNode benchmarks(String tenant, String actor) {
        TenantService.Tenant me = tenants.require(tenant);
        boolean contributes = optedIn(me);
        Map<String, Map<String, M>> mine = tenantMetrics(tenant);
        Map<String, Map<String, ObjectNode>> peers = contributes ? peerCells() : Map.of();
        ObjectNode out = Json.obj().put("generatedAt", Instant.now().toString()).put("k", K).put("optedIn", contributes);
        out.put("note", contributes ? "Peers: de-identified aggregates across opted-in organisations; cells with fewer than " + K + " organisations are suppressed."
                : "Your organisation does not contribute to benchmarking – opt in to see anonymised peer aggregates.");
        ArrayNode rows = out.putArray("rows");
        for (String dim : DIMENSIONS) {
            Map<String, M> own = mine.get(dim);
            Map<String, ObjectNode> peer = peers.getOrDefault(dim, Map.of());
            java.util.TreeSet<String> keys = new java.util.TreeSet<>(own.keySet());
            peer.forEach((k, v) -> { if (v != null) keys.add(k); });
            for (String key : keys) {
                ObjectNode r = rows.addObject().put("dimension", dim).put("key", key);
                if (own.containsKey(key)) r.set("own", own(own.get(key)));
                if (!contributes) r.put("peers", "NOT_OPTED_IN");
                else if (peer.get(key) == null) r.put("peers", "SUPPRESSED");
                else r.set("peers", peer.get(key));
            }
        }
        audit.record(tenant, actor, "benchmark.view", "benchmarks", Map.of("optedIn", contributes));
        return out;
    }

    /** Opt-in / opt-out (TENANT_ADMIN, checked by the controller). */
    public boolean setOptIn(String tenant, boolean optIn, String actor) {
        tenants.updateSettings(tenant, Json.obj().put("benchmarkOptIn", optIn), actor);
        audit.record(tenant, actor, optIn ? "benchmark.opt-in" : "benchmark.opt-out", "tenant/" + tenant, null);
        return optIn;
    }

    /** Contributes released (k-anonymous) cells to the knowledge base; returns how many were contributed. */
    public int contributeToKnowledgeBase() {
        int n = 0;
        for (var dim : peerCells().entrySet()) {
            for (var cell : dim.getValue().entrySet()) {
                if (cell.getValue() != null && kb.contributeAggregate(dim.getKey(), cell.getKey(), cell.getValue())) n++;
            }
        }
        return n;
    }

    @Scheduled(cron = "${intimed.benchmarks.cron:0 47 3 * * *}")
    public void nightly() {
        if (!enabled) return;
        try { LOG.info("benchmark aggregates contributed to the knowledge base: {}", contributeToKnowledgeBase()); }
        catch (RuntimeException e) { LOG.warn("benchmark contribution failed: {}", e.getClass().getSimpleName()); }
    }

    static double round(double v, int d) { double f = Math.pow(10, d); return Math.round(v * f) / f; }
}
