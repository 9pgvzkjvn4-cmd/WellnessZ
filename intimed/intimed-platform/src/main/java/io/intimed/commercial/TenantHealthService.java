package io.intimed.commercial;

import io.intimed.common.Json;
import io.intimed.operations.RegressionService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Tenant health score 0–100 (IM-E09-BL-005) from adoption metrics, with explanation factors. Five factors of 20 points:
 * active (published) configurations, message trend (filed readings last 7 days vs the 7 days before, from metering),
 * dead-letter ratio, open security alerts and regression pass rate (last 30 days). The scoring function is pure and
 * clamped so the result is always within 0–100.
 */
@Service
public class TenantHealthService {
    static final int MAX = 20;
    private final DocumentStore store;
    private final TenantService tenants;
    private final RegressionService regression;

    public TenantHealthService(DocumentStore store, TenantService tenants, RegressionService regression) {
        this.store = store;
        this.tenants = tenants;
        this.regression = regression;
    }

    /** Inputs of the score (all non-negative; {@code regressionPassRate} −1 = no data). */
    public record Inputs(long publishedConfigs, long filedLast7, long filedPrev7, long dlqOpen, long filedTotal, long openAlerts, double regressionPassRate) { }

    public ObjectNode compute(String tenant) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        long last7 = 0, prev7 = 0;
        for (Map<String, Object> row : tenants.metering(tenant)) {
            if (!"observations.filed".equals(row.get("meter"))) continue;
            LocalDate d = LocalDate.parse((String) row.get("day"));
            long q = (Long) row.get("qty");
            if (!d.isBefore(today.minusDays(6))) last7 += q;
            else if (!d.isBefore(today.minusDays(13))) prev7 += q;
        }
        Inputs in = new Inputs(store.countByStatus(tenant, "config", "PUBLISHED"), last7, prev7, store.countByStatus(tenant, "dlq", "OPEN"),
                store.countByStatus(tenant, "observation", "FILED"), store.countByStatus(tenant, "security-alert", "OPEN"), regression.passRate(tenant, 30));
        ObjectNode r = score(in);
        r.put("tenant", tenant).put("generatedAt", Instant.now().toString());
        return r;
    }

    public static ObjectNode score(Inputs in) {
        ObjectNode r = Json.obj();
        ArrayNode f = r.putArray("factors");
        double total = 0;
        // 1. adoption: published configurations (5+ = full)
        double p1 = MAX * Math.min(1.0, Math.max(0, in.publishedConfigs()) / 5.0);
        total += factor(f, "activeConfigurations", in.publishedConfigs(), p1, in.publishedConfigs() == 0 ? "No published device configuration – nothing is live yet"
                : in.publishedConfigs() + " published configuration(s); 5 or more earns full points");
        // 2. message trend
        double p2;
        String e2;
        if (in.filedLast7() == 0 && in.filedPrev7() == 0) { p2 = 0; e2 = "No readings filed in the last 14 days"; }
        else if (in.filedPrev7() == 0) { p2 = MAX; e2 = in.filedLast7() + " readings this week (new traffic)"; }
        else {
            double ratio = in.filedLast7() / (double) in.filedPrev7();
            p2 = MAX * Math.min(1.0, ratio);
            e2 = in.filedLast7() + " readings this week vs " + in.filedPrev7() + " the week before (" + Math.round((ratio - 1) * 100) + " %)";
        }
        total += factor(f, "messageTrend", in.filedLast7(), p2, e2);
        // 3. dead-letter ratio (10 % or more = 0 points)
        double ratio = in.dlqOpen() / (double) Math.max(1, in.filedTotal() + in.dlqOpen());
        double p3 = in.filedTotal() == 0 && in.dlqOpen() == 0 ? MAX / 2.0 : MAX * (1 - Math.min(1.0, ratio * 10));
        total += factor(f, "deadLetterRatio", Math.round(ratio * 1000) / 10.0, p3, in.filedTotal() == 0 && in.dlqOpen() == 0
                ? "No traffic yet – neutral" : in.dlqOpen() + " open dead letter(s), " + Math.round(ratio * 1000) / 10.0 + " % of messages");
        // 4. open alerts (−5 points each)
        double p4 = Math.max(0, MAX - 5.0 * Math.max(0, in.openAlerts()));
        total += factor(f, "openAlerts", in.openAlerts(), p4, in.openAlerts() == 0 ? "No open security alerts" : in.openAlerts() + " open security alert(s) – 5 points each");
        // 5. regression pass rate
        double p5 = in.regressionPassRate() < 0 ? MAX / 2.0 : MAX * Math.min(1.0, in.regressionPassRate());
        total += factor(f, "regressionPassRate", in.regressionPassRate() < 0 ? -1 : Math.round(in.regressionPassRate() * 1000) / 10.0, p5,
                in.regressionPassRate() < 0 ? "No scheduled regression runs in 30 days – neutral" : Math.round(in.regressionPassRate() * 100) + " % of regression runs passed (30 days)");
        int score = (int) Math.max(0, Math.min(100, Math.round(total)));
        r.put("score", score).put("grade", score >= 80 ? "HEALTHY" : score >= 50 ? "AT_RISK" : "CRITICAL");
        return r;
    }

    private static double factor(ArrayNode f, String name, double value, double points, String explanation) {
        double p = Math.max(0, Math.min(MAX, points));
        f.addObject().put("factor", name).put("value", value).put("points", Math.round(p * 10) / 10.0).put("maxPoints", MAX).put("explanation", explanation);
        return p;
    }
}
