package io.intimed.commercial;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.MasterKey;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import io.intimed.tenancy.TenantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Billing / metering export and reconciliation (IM-E09-BL-002). The export lists every tenant × meter × day quantity of
 * a month as CSV and as JSON lines; each body is signed with HMAC-SHA256 under a per-tenant export key held in the
 * {@link SecretStore} ({@code billing/export-hmac}, generated on first use) – or, when the secrets provider cannot store
 * it, a key derived from the master key. Reconciliation compares uploaded invoice lines with metering and reports every
 * difference. Exports contain usage counts only (no PHI, no subject or device identifiers).
 */
@Service
public class BillingService {
    public static final String SECRET_NAME = "billing/export-hmac";
    static final int MAX_INVOICE_LINES = 5000;
    private static final Logger LOG = LoggerFactory.getLogger(BillingService.class);
    private final TenantService tenants;
    private final SecretStore secrets;
    private final DocumentStore store;
    private final AuditService audit;

    public BillingService(TenantService tenants, SecretStore secrets, DocumentStore store, AuditService audit) {
        this.tenants = tenants;
        this.secrets = secrets;
        this.store = store;
        this.audit = audit;
    }

    public record Key(String id, byte[] bytes) { }

    Key key(String tenant, String actor) {
        try {
            Optional<String> v = secrets.get(tenant, SECRET_NAME);
            if (v.isEmpty()) {
                secrets.put(tenant, SECRET_NAME, Base64.getEncoder().encodeToString(Hashing.randomBytes(32)), actor);
                v = secrets.get(tenant, SECRET_NAME);
            }
            if (v.isPresent()) return new Key(SecretStore.PREFIX + SECRET_NAME, Base64.getDecoder().decode(v.get()));
        } catch (RuntimeException e) {
            LOG.warn("billing export key not available from the secrets provider ({}); using the derived key", e.getClass().getSimpleName());
        }
        return new Key("derived:billing-export/" + tenant, MasterKey.derive("billing-export/" + tenant));
    }

    public record Row(String tenant, String day, String meter, long qty) { }

    public List<Row> rows(String tenant, YearMonth m) {
        List<Row> out = new ArrayList<>();
        for (Map<String, Object> r : tenants.metering(tenant)) {
            String day = (String) r.get("day");
            if (day.startsWith(m.toString())) out.add(new Row(tenant, day, (String) r.get("meter"), (Long) r.get("qty")));
        }
        out.sort((a, b) -> a.day().equals(b.day()) ? a.meter().compareTo(b.meter()) : a.day().compareTo(b.day()));
        return out;
    }

    public static String csv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("tenant,day,meter,qty\n");
        for (Row r : rows) sb.append(r.tenant()).append(',').append(r.day()).append(',').append(r.meter()).append(',').append(r.qty()).append('\n');
        return sb.toString();
    }

    public static String jsonLines(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) sb.append(Json.write(Json.obj().put("tenant", r.tenant()).put("day", r.day()).put("meter", r.meter()).put("qty", r.qty()))).append('\n');
        return sb.toString();
    }

    public static String sign(byte[] key, String body) { return Hashing.hmacSha256(key, body); }

    /** Full export envelope: both bodies plus their signatures. */
    public ObjectNode export(String tenant, String month, String actor) {
        YearMonth m = MonthlyReportService.parseMonth(month);
        List<Row> rows = rows(tenant, m);
        Key k = key(tenant, actor);
        String csv = csv(rows), jsonl = jsonLines(rows);
        ObjectNode o = Json.obj().put("tenant", tenant).put("month", m.toString()).put("rows", rows.size()).put("generatedAt", Instant.now().toString())
                .put("algorithm", "HMAC-SHA256").put("keyId", k.id());
        Map<String, Long> totals = new TreeMap<>();
        rows.forEach(r -> totals.merge(r.meter(), r.qty(), Long::sum));
        ObjectNode t = o.putObject("totals");
        totals.forEach(t::put);
        o.put("csv", csv).put("jsonl", jsonl);
        o.putObject("signatures").put("csv", sign(k.bytes(), csv)).put("jsonl", sign(k.bytes(), jsonl));
        audit.record(tenant, actor, "billing.export", "billing/" + m, Map.of("rows", rows.size(), "keyId", k.id()));
        return o;
    }

    public boolean verify(String tenant, String body, String signature, String actor) {
        return Hashing.equalsConstantTime(sign(key(tenant, actor).bytes(), body), signature == null ? "" : signature.replaceFirst("^hmac-sha256=", ""));
    }

    /**
     * Compares invoice lines {@code [{meter, day?, qty}]} with metering. Lines with a day are compared per day; lines
     * without a day are compared with the monthly total of that meter. Metered usage that is not invoiced is reported too.
     */
    public ObjectNode reconcile(String tenant, JsonNode body, String actor) {
        YearMonth m = MonthlyReportService.parseMonth(body.path("month").asString(null));
        JsonNode lines = body.path("lines");
        if (!lines.isArray() || lines.isEmpty()) throw ApiException.bad("lines-required", "lines must be a non-empty array");
        if (lines.size() > MAX_INVOICE_LINES) throw ApiException.bad("too-many-lines", "at most " + MAX_INVOICE_LINES + " invoice lines");
        Map<String, Long> meteredDay = new TreeMap<>(), meteredMonth = new TreeMap<>();
        for (Row r : rows(tenant, m)) {
            meteredDay.merge(r.meter() + "|" + r.day(), r.qty(), Long::sum);
            meteredMonth.merge(r.meter(), r.qty(), Long::sum);
        }
        Map<String, Long> invDay = new TreeMap<>(), invMonth = new TreeMap<>();
        for (JsonNode l : lines) {
            String meter = l.path("meter").asString("");
            if (!meter.matches("[a-z0-9._-]{1,64}")) throw ApiException.bad("invalid-meter", "invalid meter name");
            if (!l.path("qty").isIntegralNumber() || l.get("qty").asLong() < 0) throw ApiException.bad("invalid-qty", "qty must be a non-negative integer");
            long q = l.get("qty").asLong();
            if (l.hasNonNull("day")) {
                String day = l.get("day").asString();
                if (!day.matches("\\d{4}-\\d{2}-\\d{2}") || !day.startsWith(m.toString())) throw ApiException.bad("invalid-day", "day must be YYYY-MM-DD within " + m);
                invDay.merge(meter + "|" + day, q, Long::sum);
            } else {
                invMonth.merge(meter, q, Long::sum);
            }
        }
        ObjectNode out = Json.obj().put("tenant", tenant).put("month", m.toString()).put("generatedAt", Instant.now().toString());
        ArrayNode diffs = out.putArray("differences");
        int matched = 0;
        // per-day comparison for meters invoiced by day
        TreeSet<String> dayMeters = new TreeSet<>();
        invDay.keySet().forEach(k -> dayMeters.add(k.substring(0, k.indexOf('|'))));
        TreeSet<String> dayKeys = new TreeSet<>(invDay.keySet());
        meteredDay.keySet().stream().filter(k -> dayMeters.contains(k.substring(0, k.indexOf('|')))).forEach(dayKeys::add);
        for (String k : dayKeys) {
            long inv = invDay.getOrDefault(k, 0L), met = meteredDay.getOrDefault(k, 0L);
            String[] p = k.split("\\|");
            if (inv == met) matched++;
            else diffs.addObject().put("meter", p[0]).put("day", p[1]).put("invoiced", inv).put("metered", met).put("delta", inv - met)
                    .put("kind", met == 0 ? "INVOICED_NOT_METERED" : inv == 0 ? "METERED_NOT_INVOICED" : "QUANTITY_MISMATCH");
        }
        // monthly comparison for the remaining meters
        TreeSet<String> monthKeys = new TreeSet<>(invMonth.keySet());
        meteredMonth.keySet().stream().filter(k -> !dayMeters.contains(k)).forEach(monthKeys::add);
        for (String meter : monthKeys) {
            long inv = invMonth.getOrDefault(meter, 0L), met = meteredMonth.getOrDefault(meter, 0L);
            if (inv == met) matched++;
            else diffs.addObject().put("meter", meter).put("invoiced", inv).put("metered", met).put("delta", inv - met)
                    .put("kind", met == 0 ? "INVOICED_NOT_METERED" : inv == 0 ? "METERED_NOT_INVOICED" : "QUANTITY_MISMATCH");
        }
        out.put("matched", matched).put("differenceCount", diffs.size()).put("status", diffs.isEmpty() ? "RECONCILED" : "DIFFERENCES");
        String id = Hashing.id("rec");
        out.put("id", id);
        store.put(tenant, "billing-reconciliation", id, out, out.get("status").asString(), m.toString());
        audit.record(tenant, actor, "billing.reconcile", "billing/" + m, Map.of("status", out.get("status").asString(), "differences", diffs.size(), "lines", lines.size()));
        return out;
    }
}
