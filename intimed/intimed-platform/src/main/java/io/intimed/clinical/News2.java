package io.intimed.clinical;

import io.intimed.common.Json;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * NEWS2-style aggregate (Royal College of Physicians, National Early Warning Score 2, 2017) for simulated time series
 * (IM-E13-BL-005). Informational engineering output only – IntiMed raises no alarms and does not escalate. SpO2 scale 1,
 * air (no supplemental oxygen) and consciousness "alert" are assumed; parameters the driver does not deliver are listed as
 * missing and the score is then partial.
 */
public final class News2 {
    private News2() { }

    public static final List<String> PARAMETERS = List.of("resp-rate", "spo2", "systolic", "heart-rate", "body-temp");

    public static int rr(double v) { long x = Math.round(v); return x <= 8 ? 3 : x <= 11 ? 1 : x <= 20 ? 0 : x <= 24 ? 2 : 3; }
    public static int spo2(double v) { long x = Math.round(v); return x <= 91 ? 3 : x <= 93 ? 2 : x <= 95 ? 1 : 0; }
    public static int systolic(double v) { long x = Math.round(v); return x <= 90 ? 3 : x <= 100 ? 2 : x <= 110 ? 1 : x <= 219 ? 0 : 3; }
    public static int pulse(double v) { long x = Math.round(v); return x <= 40 ? 3 : x <= 50 ? 1 : x <= 90 ? 0 : x <= 110 ? 1 : x <= 130 ? 2 : 3; }
    public static int temp(double v) { double x = Math.round(v * 10) / 10.0; return x <= 35.0 ? 3 : x <= 36.0 ? 1 : x <= 38.0 ? 0 : x <= 39.0 ? 1 : 2; }

    /** @param values concept (KB entry id) → value in the knowledge-base unit */
    public static ObjectNode score(Map<String, Double> values) {
        ObjectNode o = Json.obj();
        ObjectNode comp = o.putObject("components");
        int total = 0;
        boolean single3 = false;
        var missing = o.putArray("missing");
        for (String p : PARAMETERS) {
            Double v = values.get(p);
            if (v == null) { missing.add(p); continue; }
            int s = switch (p) { case "resp-rate" -> rr(v); case "spo2" -> spo2(v); case "systolic" -> systolic(v); case "heart-rate" -> pulse(v); default -> temp(v); };
            comp.put(p, s);
            total += s;
            single3 |= s == 3;
        }
        String band = total >= 7 ? "high" : total >= 5 ? "medium" : single3 ? "low-medium (single parameter 3)" : "low";
        o.put("score", total).put("band", band).put("partial", !missing.isEmpty()).put("assumed", "air, alert (ACVPU = A)")
                .put("note", "informational – NEWS2-style aggregate of simulated values; no alarm, no escalation");
        return o;
    }
}
