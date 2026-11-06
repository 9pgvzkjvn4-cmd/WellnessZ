package io.intimed.console.intelligence;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.privacy.PhiMasker;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * De-identified knowledge base of certified configurations (IM-E03-BL-003, SC-15, SC-19): fingerprints = model, driver,
 * option, generic parameter values, host template type and outcome – no PHI, no tenant id, no host names, paths, ports,
 * identifiers or secrets. Only certified runs contribute (poisoning guard) and only from tenants that opted in.
 */
@Service
public class KnowledgeBase {
    /** Parameters that describe a vendor/protocol choice and are safe to share; everything else is dropped. */
    public static final Set<String> SHAREABLE = Set.of("baud", "parity", "dataBits", "stopBits", "flowControl", "terminator", "mode", "pollSeconds",
            "stabilitySeconds", "archive", "encoding", "pairing", "bondingOwner", "reconnect", "tls", "tlsMin", "ackMode", "profile", "framing",
            "qcLotPolicy", "rateLimitPerMinute", "metricSubscription", "confidenceThreshold", "profileValidation", "intervalMinutes");
    private final DocumentStore store;

    public KnowledgeBase(DocumentStore store) { this.store = store; }

    public ObjectNode fingerprint(JsonNode cfg, String outcome) {
        ObjectNode f = Json.obj().put("modelId", cfg.path("modelId").asString()).put("driver", cfg.path("driver").asString())
                .put("option", cfg.path("option").asString()).put("association", cfg.at("/association/method").asString())
                .put("outcome", outcome).put("contributedAt", Instant.now().toString().substring(0, 10));
        ObjectNode p = f.putObject("params");
        cfg.path("params").properties().forEach(e -> { if (SHAREABLE.contains(e.getKey()) && !e.getValue().asString("").startsWith("secret://")) p.set(e.getKey(), e.getValue()); });
        if (cfg.has("intervalMinutes")) p.put("intervalMinutes", cfg.get("intervalMinutes").asInt());
        f.set("templateTypes", Json.arr());
        cfg.path("bindings").forEach(b -> f.withArray("templateTypes").add(b.path("templateId").asString()));
        return f;
    }

    public void contribute(boolean optIn, JsonNode cfg, String outcome) {
        if (!optIn) return;
        ObjectNode f = fingerprint(cfg, outcome);
        String masked = PhiMasker.mask(Json.write(f));
        if (!masked.equals(Json.write(f))) return; // defence in depth: refuse anything that looks identifying
        store.put(DocumentStore.GLOBAL, "kb-fingerprint", Hashing.id("kb"), f, outcome, cfg.path("driver").asString() + "/" + cfg.path("option").asString());
    }

    public record Stat(String value, long runs, long passed, double successRate) { }

    /** Most successful value for a parameter among certified fingerprints of the same driver and option. */
    public Stat best(String driver, String option, String param) {
        Map<String, long[]> counts = new HashMap<>();
        for (ObjectNode f : store.listByRef(DocumentStore.GLOBAL, "kb-fingerprint", driver + "/" + option)) {
            JsonNode v = f.path("params").get(param);
            if (v == null) continue;
            long[] c = counts.computeIfAbsent(v.asString(), k -> new long[2]);
            c[0]++;
            if ("CERTIFIED".equals(f.get("outcome").asString())) c[1]++;
        }
        return counts.entrySet().stream().max((a, b) -> Long.compare(a.getValue()[1], b.getValue()[1]))
                .map(e -> new Stat(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[0] == 0 ? 0 : (double) e.getValue()[1] / e.getValue()[0])).orElse(null);
    }

    public long runs(String driver, String option) { return store.listByRef(DocumentStore.GLOBAL, "kb-fingerprint", driver + "/" + option).size(); }

    public List<ObjectNode> export() { return store.list(DocumentStore.GLOBAL, "kb-fingerprint"); }

    /**
     * De-identified cross-tenant benchmark aggregate (IM-E02-BL-007 / IM-E09-BL-004): only k-anonymous cells (k ≥ 5
     * organisations) reach the knowledge base; the aggregate carries no tenant id and is refused if it looks identifying.
     */
    public boolean contributeAggregate(String dimension, String key, ObjectNode aggregate) {
        if (!dimension.matches("[a-z]{1,20}") || key == null || key.length() > 120) return false;
        String plain = Json.write(aggregate);
        if (!PhiMasker.mask(plain).equals(plain)) return false;
        String id = dimension + ":" + key;
        ObjectNode doc = Json.obj().put("dimension", dimension).put("key", key).put("updatedAt", Instant.now().toString().substring(0, 10));
        doc.set("aggregate", aggregate);
        store.put(DocumentStore.GLOBAL, "kb-benchmark", Hashing.stableId("kbb", id), doc, "PUBLISHED", id);
        return true;
    }

    public List<ObjectNode> benchmarkAggregates() { return store.list(DocumentStore.GLOBAL, "kb-benchmark"); }
}
