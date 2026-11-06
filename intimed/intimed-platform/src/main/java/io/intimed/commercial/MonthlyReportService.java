package io.intimed.commercial;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.console.ConfigService;
import io.intimed.operations.RegressionService;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Monthly executive analytics report per tenant (IM-E09-BL-003): capture rate, clinician-verified readings, estimated
 * time saved, data quality (dead-letter / unmatched / held rates), calibration compliance, fleet health, regression pass
 * rate and metered usage – computed from the daily metering history and stored documents. Harness (TEST mode)
 * traffic is excluded from clinical KPIs. The report holds counts and rates only (no PHI) and is persisted as a
 * {@code monthly-report} snapshot (FINAL for closed months, PROVISIONAL for the running month).
 */
@Service
public class MonthlyReportService {
    public static final String KIND = "monthly-report";
    /** Planning assumption of the requirement pack: ≥ 1 min (acute) to ≥ 3 min (OH) of manual transcription saved per reading. */
    static final double MINUTES_SAVED_PER_READING = 1.5;
    private final DocumentStore store;
    private final TenantService tenants;
    private final ConfigService configs;
    private final AuditService audit;

    public MonthlyReportService(DocumentStore store, TenantService tenants, ConfigService configs, AuditService audit) {
        this.store = store;
        this.tenants = tenants;
        this.configs = configs;
        this.audit = audit;
    }

    public static YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) return YearMonth.now(ZoneOffset.UTC);
        if (!month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw ApiException.bad("invalid-month", "month must be YYYY-MM");
        YearMonth m = YearMonth.parse(month);
        if (m.isAfter(YearMonth.now(ZoneOffset.UTC))) throw ApiException.bad("invalid-month", "month is in the future");
        if (m.isBefore(YearMonth.of(2020, 1))) throw ApiException.bad("invalid-month", "month is out of range");
        return m;
    }

    static boolean inMonth(JsonNode doc, YearMonth m, String... fields) {
        for (String f : fields) {
            String v = doc.path(f).asString("");
            if (v.length() >= 7) return v.startsWith(m.toString());
        }
        return false;
    }

    public ObjectNode generate(String tenant, String month, String actor) {
        YearMonth m = parseMonth(month);
        TenantService.Tenant t = tenants.require(tenant);
        ObjectNode r = Json.obj().put("tenant", t.id()).put("tenantName", t.name()).put("month", m.toString())
                .put("status", m.isBefore(YearMonth.now(ZoneOffset.UTC)) ? "FINAL" : "PROVISIONAL").put("generatedAt", Instant.now().toString());

        // ---- readings (LIVE + SHADOW traffic only)
        List<ObjectNode> obs = store.list(tenant, "observation").stream()
                .filter(o -> !"TEST".equals(o.path("mode").asString())).filter(o -> inMonth(o, m, "receivedAt", "effectiveTime")).toList();
        long total = obs.size();
        long filed = obs.stream().filter(o -> "FILED".equals(o.path("status").asString())).count();
        long held = obs.stream().filter(o -> o.path("status").asString().startsWith("HELD")).count();
        long unmatchedObs = obs.stream().filter(o -> "UNMATCHED".equals(o.path("status").asString())).count();
        long verified = obs.stream().filter(o -> o.has("verifiedBy")).count();
        long dlq = store.list(tenant, "dlq").stream().filter(d -> !"TEST".equals(d.path("mode").asString())).filter(d -> inMonth(d, m, "createdAt")).count();
        long unmatched = store.list(tenant, "unmatched").stream().filter(d -> !"TEST".equals(d.path("mode").asString())).filter(d -> inMonth(d, m, "createdAt")).count();
        long attempts = total + dlq;
        ObjectNode readings = r.putObject("readings").put("received", attempts).put("acquired", total).put("filed", filed).put("held", held)
                .put("unmatched", Math.max(unmatched, unmatchedObs)).put("deadLetters", dlq).put("verifiedByClinician", verified)
                .put("captureRatePct", pct(filed, attempts));
        readings.put("estimatedMinutesSaved", Math.round(filed * MINUTES_SAVED_PER_READING))
                .put("estimatedHoursSaved", Math.round(filed * MINUTES_SAVED_PER_READING / 6.0) / 10.0);
        r.putObject("dataQuality").put("dlqRatePct", pct(dlq, attempts)).put("unmatchedRatePct", pct(Math.max(unmatched, unmatchedObs), attempts))
                .put("heldRatePct", pct(held, attempts)).put("verifiedRatePct", pct(verified, filed));

        // ---- fleet & calibration
        List<ObjectNode> fleet = configs.fleet(tenant);
        Map<String, Long> health = new TreeMap<>(Map.of("GREEN", 0L, "AMBER", 0L, "RED", 0L));
        fleet.forEach(f -> health.merge(f.path("health").asString("GREEN"), 1L, Long::sum));
        ObjectNode fl = r.putObject("fleet").put("devices", fleet.size());
        health.forEach(fl::put);
        fl.put("healthyPct", fleet.isEmpty() ? 100 : pct(health.get("GREEN"), fleet.size()));
        List<ObjectNode> pub = store.listByStatus(tenant, "config", "PUBLISHED");
        long calOk = pub.stream().filter(c -> !"LOCKED".equals(c.at("/calibration/status").asString())).count();
        long calDueInMonth = pub.stream().filter(c -> c.at("/calibration/dueDate").asString("").startsWith(m.toString())).count();
        r.putObject("calibration").put("publishedConfigs", pub.size()).put("compliancePct", pub.isEmpty() ? 100 : pct(calOk, pub.size()))
                .put("locked", pub.size() - calOk).put("dueThisMonth", calDueInMonth);

        // ---- regression & exchange tests
        List<ObjectNode> reg = store.list(tenant, RegressionService.KIND).stream().filter(x -> inMonth(x, m, "startedAt")).toList();
        long regPassed = reg.stream().filter(x -> "PASSED".equals(x.path("status").asString())).count();
        r.putObject("regression").put("runs", reg.size()).put("passed", regPassed).put("passRatePct", reg.isEmpty() ? 0 : pct(regPassed, reg.size()));

        // ---- metering history
        Map<String, Long> meters = new TreeMap<>();
        Map<String, Long> daily = new TreeMap<>();
        for (Map<String, Object> row : tenants.metering(tenant)) {
            String day = (String) row.get("day");
            if (!day.startsWith(m.toString())) continue;
            meters.merge((String) row.get("meter"), (Long) row.get("qty"), Long::sum);
            if ("observations.filed".equals(row.get("meter"))) daily.merge(day, (Long) row.get("qty"), Long::sum);
        }
        ObjectNode mt = r.putObject("metering");
        meters.forEach(mt::put);
        ObjectNode dl = r.putObject("dailyFiled");
        daily.forEach(dl::put);
        r.put("note", "De-identified operational counts; time saved uses the planning assumption of " + MINUTES_SAVED_PER_READING
                + " min per filed reading. Exchange-harness traffic is excluded from clinical KPIs.");

        store.put(tenant, KIND, m.toString(), r, r.get("status").asString(), m.toString());
        audit.record(tenant, actor, "report.monthly", "report/" + m, Map.of("status", r.get("status").asString()));
        return r;
    }

    public List<ObjectNode> snapshots(String tenant) { return store.list(tenant, KIND); }

    /** Flat metric,value CSV of a report (RFC 4180 quoting; values are numbers or controlled strings). */
    public static String csv(ObjectNode r) {
        StringBuilder sb = new StringBuilder("metric,value\r\n");
        flatten("", r, sb);
        return sb.toString();
    }

    private static void flatten(String prefix, JsonNode n, StringBuilder sb) {
        if (n.isObject()) {
            n.properties().forEach(e -> flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), sb));
        } else {
            sb.append(cell(prefix)).append(',').append(cell(n.asString())).append("\r\n");
        }
    }

    static String cell(String v) {
        String s = v == null ? "" : v;
        // CSV-injection guard: never let a spreadsheet interpret a cell as a formula
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    static double pct(long a, long b) { return b == 0 ? 0 : Math.round(a * 1000.0 / b) / 10.0; }
}
