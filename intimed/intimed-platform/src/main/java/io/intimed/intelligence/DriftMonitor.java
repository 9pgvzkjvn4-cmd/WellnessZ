package io.intimed.intelligence;

import io.intimed.common.Hashing;
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
 */
@Service
public class DriftMonitor {
    private final DocumentStore store;

    public DriftMonitor(DocumentStore store) { this.store = store; }

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
        for (ObjectNode a : alerts) {
            String id = Hashing.stableId("drift", a.get("configId").asString() + a.get("key").asString() + Instant.now().toString().substring(0, 13));
            ObjectNode al = a.deepCopy().put("id", id).put("rule", "AN-08 data-quality drift").put("raisedBy", actor).put("createdAt", Instant.now().toString())
                    .put("advice", "check calibration / QC, probe placement and firmware; compare with a reference device");
            store.put(tenant, "drift-alert", id, al, "OPEN", a.get("configId").asString());
        }
        out.put("alerts", alerts.size());
        return out;
    }

    public List<ObjectNode> alerts(String tenant) { return store.list(tenant, "drift-alert"); }
}
