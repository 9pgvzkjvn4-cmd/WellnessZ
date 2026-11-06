package io.intimed.intelligence;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.security.audit.AuditService;
import io.intimed.common.Json;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Device data-quality drift detection (IM-E15-F01): per configuration and driver key, compares the recent mean of filed
 * values with the fleet baseline of the same key (all other configurations of the same driver) as a z-score; also the
 * share of abnormal interpretation flags. A |z| ≥ 3 or an abnormal-flag share ≥ 60 % raises a "drift-alert" for the
 * biomedical team (calibration, probe placement, firmware) – a data-quality signal, never a clinical alarm.
 * <p>
 * Alert workflow (IM-E15-BL-003), modelled on the calibration/recall acknowledgement pattern: OPEN → ACKNOWLEDGED (note,
 * optional link to a calibration record of the configuration) → RESOLVED (manually with a note, or automatically when
 * the next analysis no longer alerts for the configuration/key). Every transition is written to the tamper-evident audit
 * trail, which forwards each record to the SIEM sink ({@code siemForwarded} on the alert).
 */
@Service
public class DriftMonitor {
    public static final String KIND = "drift-alert";
    private final DocumentStore store;
    private final AuditService audit;

    public DriftMonitor(DocumentStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    record Stats(int n, double mean, double m2, int abnormal) {
        Stats add(double x, boolean ab) {
            int n2 = n + 1;
            double d = x - mean, mean2 = mean + d / n2;
            return new Stats(n2, mean2, m2 + d * (x - mean2), abnormal + (ab ? 1 : 0));
        }
        double sd() { return n < 2 ? 0 : Math.sqrt(m2 / (n - 1)); }
    }

    public ObjectNode analyse(String tenant, String actor) {
        Map<String, Map<String, Stats>> perConfig = new HashMap<>();
        Map<String, String> driverOf = new HashMap<>();
        for (ObjectNode o : store.list(tenant, "observation")) {
            if (!"FILED".equals(o.path("status").asString()) || "TEST".equals(o.path("mode").asString())) continue;
            String cfg = o.path("configId").asString();
            driverOf.put(cfg, o.at("/driver/ref").asString());
            for (JsonNode m : o.path("measurements")) {
                if (!m.path("value").isNumber()) continue;
                perConfig.computeIfAbsent(cfg, k -> new HashMap<>()).merge(m.get("key").asString(), new Stats(0, 0, 0, 0).add(m.get("value").asDouble(), false),
                        (a, b) -> a.add(b.mean(), false));
            }
        }
        // abnormal-flag share from filed live template instances
        Map<String, int[]> flags = new HashMap<>();
        for (ObjectNode i : store.list(tenant, "template-instance")) {
            String cfg = i.path("configId").asString();
            i.path("fields").properties().forEach(e -> {
                int[] c = flags.computeIfAbsent(cfg + "|" + e.getValue().path("sourceKey").asString(), k -> new int[2]);
                if (e.getValue().path("series").isArray()) {
                    for (JsonNode pt : e.getValue().get("series")) {
                        if (!pt.has("f")) continue;
                        c[0]++;
                        if (!"N".equals(pt.get("f").asString())) c[1]++;
                    }
                    return;
                }
                String code = e.getValue().at("/interpretation/code").asString(null);
                if (code == null) return;
                c[0]++;
                if (!"N".equals(code)) c[1]++;
            });
        }
        ObjectNode out = Json.obj().put("analysedAt", Instant.now().toString());
        ArrayNode rows = out.putArray("signals");
        List<ObjectNode> alerts = new ArrayList<>();
        for (var c : perConfig.entrySet()) {
            String drv = driverOf.get(c.getKey());
            for (var k : c.getValue().entrySet()) {
                Stats mine = k.getValue();
                Stats fleet = new Stats(0, 0, 0, 0);
                for (var other : perConfig.entrySet()) {
                    if (other.getKey().equals(c.getKey()) || !drv.equals(driverOf.get(other.getKey()))) continue;
                    Stats s = other.getValue().get(k.getKey());
                    if (s != null) fleet = fleet.add(s.mean(), false);
                }
                double sd = Math.max(fleet.sd(), Math.abs(fleet.mean()) * 0.02);
                Double z = fleet.n() < 2 || sd == 0 ? null : (mine.mean() - fleet.mean()) / sd;
                int[] fl = flags.getOrDefault(c.getKey() + "|" + k.getKey(), new int[2]);
                double abShare = fl[0] == 0 ? 0 : (double) fl[1] / fl[0];
                ObjectNode r = rows.addObject().put("configId", c.getKey()).put("driver", drv).put("key", k.getKey()).put("n", mine.n())
                        .put("mean", Math.round(mine.mean() * 100) / 100.0).put("fleetConfigs", fleet.n()).put("abnormalShare", Math.round(abShare * 100) / 100.0);
                if (z != null) r.put("z", Math.round(z * 100) / 100.0).put("fleetMean", Math.round(fleet.mean() * 100) / 100.0);
                boolean alert = (z != null && Math.abs(z) >= 3 && mine.n() >= 3) || (fl[0] >= 5 && abShare >= 0.6);
                r.put("alert", alert);
                if (alert) alerts.add(r);
            }
        }
        java.util.Set<String> alerting = new java.util.HashSet<>();
        int raised = 0;
        for (ObjectNode a : alerts) {
            String ck = a.get("configId").asString() + "|" + a.get("key").asString();
            alerting.add(ck);
            // an unresolved alert for the same configuration/key is updated, not duplicated
            ObjectNode existing = store.list(tenant, KIND).stream().filter(x -> ck.equals(x.path("configId").asString() + "|" + x.path("key").asString()))
                    .filter(x -> List.of("OPEN", "ACKNOWLEDGED").contains(x.path("status").asString())).findFirst().orElse(null);
            if (existing != null) {
                for (String f : List.of("n", "mean", "fleetConfigs", "abnormalShare", "z", "fleetMean")) if (a.has(f)) existing.set(f, a.get(f));
                existing.put("lastSeenAt", Instant.now().toString()).put("occurrences", existing.path("occurrences").asInt(1) + 1);
                store.put(tenant, KIND, existing.get("id").asString(), existing, existing.get("status").asString(), a.get("configId").asString());
                continue;
            }
            String id = Hashing.stableId("drift", ck + Instant.now().toString());
            ObjectNode al = a.deepCopy().put("id", id).put("rule", "AN-08 data-quality drift").put("raisedBy", actor).put("createdAt", Instant.now().toString())
                    .put("lastSeenAt", Instant.now().toString()).put("occurrences", 1).put("status", "OPEN")
                    .put("advice", "check calibration / QC, probe placement and firmware; compare with a reference device");
            al.putArray("history").addObject().put("status", "OPEN").put("by", actor).put("at", Instant.now().toString());
            siem(tenant, actor, "drift.alert.raised", al, Map.of("configId", a.get("configId").asString(), "key", a.get("key").asString()));
            store.put(tenant, KIND, id, al, "OPEN", a.get("configId").asString());
            raised++;
        }
        // auto-resolve: OPEN / ACKNOWLEDGED alerts whose configuration/key no longer alerts in this analysis
        int resolved = 0;
        java.util.Set<String> analysed = new java.util.HashSet<>();
        for (JsonNode r : rows) analysed.add(r.get("configId").asString() + "|" + r.get("key").asString());
        for (ObjectNode x : store.list(tenant, KIND)) {
            String ck = x.path("configId").asString() + "|" + x.path("key").asString();
            if (!List.of("OPEN", "ACKNOWLEDGED").contains(x.path("status").asString()) || alerting.contains(ck) || !analysed.contains(ck)) continue;
            x.put("status", "RESOLVED").put("resolution", "AUTO").put("resolvedBy", actor).put("resolvedAt", Instant.now().toString())
                    .put("resolutionNote", "auto-resolved: the latest analysis no longer shows drift for this configuration/key");
            x.withArray("history").addObject().put("status", "RESOLVED").put("by", actor).put("at", Instant.now().toString()).put("note", "auto");
            siem(tenant, actor, "drift.alert.auto-resolved", x, Map.of("configId", x.path("configId").asString(), "key", x.path("key").asString()));
            store.put(tenant, KIND, x.get("id").asString(), x, "RESOLVED", x.path("configId").asString());
            resolved++;
        }
        out.put("alerts", alerts.size()).put("raised", raised).put("autoResolved", resolved);
        return out;
    }

    public List<ObjectNode> alerts(String tenant) { return store.list(tenant, KIND); }

    /** Acknowledge (note required); optionally links a calibration record of the configuration (its history entry time). */
    public ObjectNode acknowledge(String tenant, String id, String note, String calibrationAt, String actor) {
        ObjectNode a = store.require(tenant, KIND, id);
        if (!"OPEN".equals(a.path("status").asString("OPEN"))) throw ApiException.conflict("not-open", "only OPEN drift alerts can be acknowledged (status " + a.path("status").asString() + ")");
        if (note == null || note.isBlank()) throw ApiException.bad("note-required", "an acknowledgement note is required");
        if (calibrationAt != null && !calibrationAt.isBlank()) a.set("calibration", calibrationRecord(tenant, a, calibrationAt));
        a.put("status", "ACKNOWLEDGED").put("acknowledgedBy", actor).put("acknowledgedAt", Instant.now().toString()).put("acknowledgementNote", note);
        a.withArray("history").addObject().put("status", "ACKNOWLEDGED").put("by", actor).put("at", Instant.now().toString()).put("note", note);
        siem(tenant, actor, "drift.alert.ack", a, Map.of("calibrationLinked", a.has("calibration")));
        return store.put(tenant, KIND, id, a, "ACKNOWLEDGED", a.path("configId").asString());
    }

    /** Manual resolution with a note; a calibration record can be linked at this point too. */
    public ObjectNode resolve(String tenant, String id, String note, String calibrationAt, String actor) {
        ObjectNode a = store.require(tenant, KIND, id);
        if ("RESOLVED".equals(a.path("status").asString())) throw ApiException.conflict("resolved", "drift alert already resolved");
        if (note == null || note.isBlank()) throw ApiException.bad("note-required", "a resolution note is required");
        if (calibrationAt != null && !calibrationAt.isBlank()) a.set("calibration", calibrationRecord(tenant, a, calibrationAt));
        a.put("status", "RESOLVED").put("resolution", "MANUAL").put("resolvedBy", actor).put("resolvedAt", Instant.now().toString()).put("resolutionNote", note);
        a.withArray("history").addObject().put("status", "RESOLVED").put("by", actor).put("at", Instant.now().toString()).put("note", note);
        siem(tenant, actor, "drift.alert.resolve", a, Map.of("calibrationLinked", a.has("calibration")));
        return store.put(tenant, KIND, id, a, "RESOLVED", a.path("configId").asString());
    }

    /** The calibration check (ConfigService#calibrationCheck history entry) of the alert's configuration. */
    private ObjectNode calibrationRecord(String tenant, JsonNode alert, String at) {
        ObjectNode cfg = store.get(tenant, "config", alert.path("configId").asString()).orElseThrow(() -> ApiException.notFound("config " + alert.path("configId").asString()));
        for (JsonNode h : cfg.path("calibration").path("history")) {
            if (at.equals(h.path("at").asString())) {
                ObjectNode r = Json.obj().put("configId", cfg.get("id").asString()).put("at", at).put("by", h.path("by").asString()).put("passed", h.path("passed").asBoolean())
                        .put("note", h.path("note").asString(""));
                r.put("linkedAt", Instant.now().toString());
                return r;
            }
        }
        throw ApiException.bad("calibration-not-found", "no calibration record at " + at + " for configuration " + cfg.get("id").asString());
    }

    /** Audit record (HMAC chain) – the audit service forwards every record to the SIEM sink. */
    private void siem(String tenant, String actor, String action, ObjectNode alert, Map<String, Object> detail) {
        long seq = audit.record(tenant, actor, action, KIND + "/" + alert.path("id").asString(), detail);
        alert.put("siemForwarded", true).put("lastAuditSeq", seq);
    }
}
