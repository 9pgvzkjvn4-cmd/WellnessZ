package io.intimed.clinical;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.Role;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant interval governance (IM-E14-BL-011): a tenant-scoped, versioned overlay of the reference-interval knowledge base
 * with the life cycle DRAFT → VERIFIED → APPROVED → ACTIVE (previous ACTIVE → SUPERSEDED).
 * <ul>
 *   <li>Import from CSV ({@code entry,ageFrom,ageTo,sex,pregnant,trimester,low,high,criticalLow,criticalHigh,unit,source})
 *       or FHIR R4 ObservationDefinition {@code qualifiedInterval} (single resource or Bundle). Units must equal the
 *       knowledge-base unit of the entry – nothing is converted silently.</li>
 *   <li>CLSI EP28-A3c transference verification per partition: ≥ 20 local reference results; the proposed limits are
 *       verified when ≤ 10 % (2 of 20) fall outside them. The report (counts, min/max/mean – never the raw values) is stored
 *       with the overlay. All partitions verified → VERIFIED.</li>
 *   <li>Four-eyes approval by REGULATORY, TENANT_ADMIN or PHYSICIAN; the approver must not be the importer.</li>
 *   <li>Activation makes {@link ReferenceRanges#effective} use the overlay for live annotation, the validation studio and
 *       XT-17 (annotations show {@code source: tenant overlay vN}); every transition is audited (SIEM sink).</li>
 * </ul>
 */
@Service
public class IntervalGovernance {
    public static final String KIND = "interval-overlay";
    public static final int EP28_MIN_SAMPLES = 20;
    public static final double EP28_MAX_OUTSIDE_FRACTION = 0.10;
    public static final List<String> CSV_COLUMNS = List.of("entry", "ageFrom", "ageTo", "sex", "pregnant", "trimester", "low", "high", "criticalLow", "criticalHigh", "unit", "source");
    private static final Role[] GOVERNANCE = {Role.REGULATORY, Role.TENANT_ADMIN, Role.PHYSICIAN};
    private final DocumentStore store;
    private final AuditService audit;
    private final ReferenceRanges ranges;
    private final Map<String, Optional<JsonNode>> activeCache = new ConcurrentHashMap<>();

    public IntervalGovernance(DocumentStore store, AuditService audit, ReferenceRanges ranges) {
        this.store = store;
        this.audit = audit;
        this.ranges = ranges;
        ranges.overlaySource(this::active);
    }

    /** ACTIVE overlay of a tenant (cached; invalidated on activation). */
    public JsonNode active(String tenant) {
        if (tenant == null || DocumentStore.GLOBAL.equals(tenant)) return null;
        return activeCache.computeIfAbsent(tenant, t -> store.listByStatus(t, KIND, "ACTIVE").stream().findFirst().map(o -> (JsonNode) o)).orElse(null);
    }

    public List<ObjectNode> list(String tenant) { return store.list(tenant, KIND).reversed(); }
    public ObjectNode get(String tenant, String id) { return store.require(tenant, KIND, id); }

    // ------------------------------------------------------------------------------------------ EP28 (pure)
    /** CLSI EP28-A3c transference verification of proposed limits against local reference results. */
    public static ObjectNode ep28(BigDecimal low, BigDecimal high, List<BigDecimal> results) {
        if (results == null || results.size() < EP28_MIN_SAMPLES) throw ApiException.bad("ep28-samples", "CLSI EP28-A3c transference needs at least " + EP28_MIN_SAMPLES + " local reference results");
        if (low == null && high == null) throw ApiException.bad("ep28-limits", "partition has no limits to verify");
        int outside = 0;
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        for (BigDecimal v : results) {
            if ((low != null && v.compareTo(low) < 0) || (high != null && v.compareTo(high) > 0)) outside++;
            min = Math.min(min, v.doubleValue());
            max = Math.max(max, v.doubleValue());
            sum += v.doubleValue();
        }
        int allowed = (int) Math.floor(results.size() * EP28_MAX_OUTSIDE_FRACTION + 1e-9);
        boolean pass = outside <= allowed;
        ObjectNode r = Json.obj().put("method", "CLSI EP28-A3c transference verification").put("n", results.size()).put("outside", outside).put("allowedOutside", allowed)
                .put("outsideFraction", Math.round(1000.0 * outside / results.size()) / 1000.0).put("pass", pass)
                .put("min", min).put("max", max).put("mean", Math.round(sum / results.size() * 1000) / 1000.0)
                .put("conclusion", pass ? "proposed limits verified for local use (≤ 10 % outside)" : "transference NOT verified – more than 10 % outside: establish or re-derive the interval locally");
        if (low != null) r.put("low", low);
        if (high != null) r.put("high", high);
        return r;
    }

    // ------------------------------------------------------------------------------------------ import
    public ObjectNode importCsv(String tenant, String csv, String actor) {
        Current.require(GOVERNANCE);
        if (csv == null || csv.isBlank()) throw ApiException.bad("csv-required", "CSV content is required");
        if (csv.length() > 512 * 1024) throw ApiException.bad("csv-too-large", "CSV exceeds 512 KiB");
        String[] lines = csv.replace("\r", "").split("\n");
        List<String> head = split(lines[0]);
        for (String c : CSV_COLUMNS) if (!head.contains(c)) throw ApiException.bad("csv-header", "missing column '" + c + "' – expected " + String.join(",", CSV_COLUMNS));
        Map<String, ArrayNode> entries = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = split(lines[i]);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < head.size(); c++) row.put(head.get(c), c < cells.size() ? cells.get(c).trim() : "");
            try {
                ObjectNode r = Json.obj().put("ageFrom", num(row.get("ageFrom"), "ageFrom", BigDecimal.ZERO)).put("ageTo", num(row.get("ageTo"), "ageTo", BigDecimal.valueOf(200)));
                String sex = row.get("sex").isEmpty() ? "any" : row.get("sex").toLowerCase();
                r.put("sex", sex);
                if (Boolean.parseBoolean(row.get("pregnant")) || "1".equals(row.get("pregnant")) || "yes".equalsIgnoreCase(row.get("pregnant"))) {
                    r.put("pregnant", true).put("trimester", num(row.get("trimester"), "trimester", BigDecimal.valueOf(2)).intValue());
                }
                for (String f : List.of("low", "high", "criticalLow", "criticalHigh")) if (!row.get(f).isEmpty()) r.put(f, num(row.get(f), f, null));
                if (!row.get("source").isEmpty()) { r.put("source", row.get("source")); sources.add(row.get("source")); }
                String entry = row.get("entry");
                validateRange(entry, row.get("unit"), r);
                entries.computeIfAbsent(entry, k -> Json.arr()).add(r);
            } catch (ApiException e) {
                errors.add("line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (!errors.isEmpty()) throw ApiException.unprocessable("csv-invalid", String.join("; ", errors));
        if (entries.isEmpty()) throw ApiException.unprocessable("csv-empty", "CSV contains no interval rows");
        return create(tenant, "CSV", String.join("; ", sources), entries, actor);
    }

    /** FHIR R4 ObservationDefinition (or a Bundle of them): qualifiedInterval reference / critical ranges. */
    public ObjectNode importObservationDefinitions(String tenant, JsonNode body, String actor) {
        Current.require(GOVERNANCE);
        List<JsonNode> defs = new ArrayList<>();
        if ("Bundle".equals(body.path("resourceType").asString())) body.path("entry").forEach(e -> defs.add(e.path("resource")));
        else defs.add(body);
        Map<String, ArrayNode> entries = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        Set<String> sources = new LinkedHashSet<>();
        for (JsonNode od : defs) {
            if (!"ObservationDefinition".equals(od.path("resourceType").asString())) { errors.add("not an ObservationDefinition"); continue; }
            JsonNode entry = null;
            for (JsonNode c : od.path("code").path("coding")) {
                String sys = c.path("system").asString("");
                ObjectNode code = Json.obj().put(sys.contains("loinc") ? "loinc" : sys.contains("11073") ? "mdc" : "local", c.path("code").asString());
                entry = ranges.find(code, null);
                if (entry != null) break;
            }
            String label = od.path("id").asString(od.at("/code/text").asString("?"));
            if (entry == null) { errors.add(label + ": code not in the reference-interval knowledge base"); continue; }
            String unit = od.at("/quantitativeDetails/unit/coding/0/code").asString(entry.get("unit").asString());
            if (od.hasNonNull("publisher")) sources.add(od.get("publisher").asString());
            List<ObjectNode> refs = new ArrayList<>(), crits = new ArrayList<>();
            for (JsonNode qi : od.path("qualifiedInterval")) {
                ObjectNode r = Json.obj().put("ageFrom", qi.at("/age/low/value").asDouble(0)).put("ageTo", qi.at("/age/high/value").asDouble(200))
                        .put("sex", switch (qi.path("gender").asString("")) { case "male" -> "male"; case "female" -> "female"; default -> "any"; });
                String cond = qi.path("condition").asString("").toLowerCase();
                if (cond.contains("pregnan")) r.put("pregnant", true).put("trimester", cond.contains("1") ? 1 : cond.contains("3") ? 3 : 2);
                JsonNode lo = qi.at("/range/low/value"), hi = qi.at("/range/high/value");
                String cat = qi.path("category").asString("reference");
                if ("critical".equals(cat)) {
                    if (hi.isNumber() && !lo.isNumber()) r.put("criticalLow", hi.decimalValue());
                    else if (lo.isNumber() && !hi.isNumber()) r.put("criticalHigh", lo.decimalValue());
                    crits.add(r);
                } else if ("reference".equals(cat)) {
                    if (lo.isNumber()) r.put("low", lo.decimalValue());
                    if (hi.isNumber()) r.put("high", hi.decimalValue());
                    refs.add(r);
                }
            }
            for (ObjectNode c : crits) for (ObjectNode r : refs) {
                if (!same(c, r)) continue;
                if (c.has("criticalLow")) r.set("criticalLow", c.get("criticalLow"));
                if (c.has("criticalHigh")) r.set("criticalHigh", c.get("criticalHigh"));
            }
            for (ObjectNode r : refs) {
                try {
                    validateRange(entry.get("id").asString(), unit, r);
                    entries.computeIfAbsent(entry.get("id").asString(), k -> Json.arr()).add(r);
                } catch (ApiException e) { errors.add(label + ": " + e.getMessage()); }
            }
        }
        if (!errors.isEmpty()) throw ApiException.unprocessable("observation-definition-invalid", String.join("; ", errors));
        if (entries.isEmpty()) throw ApiException.unprocessable("no-intervals", "no reference qualifiedInterval found");
        return create(tenant, "FHIR_OBSERVATION_DEFINITION", String.join("; ", sources), entries, actor);
    }

    private static boolean same(JsonNode a, JsonNode b) {
        return a.path("sex").asString().equals(b.path("sex").asString()) && a.path("ageFrom").asDouble() == b.path("ageFrom").asDouble()
                && a.path("ageTo").asDouble() == b.path("ageTo").asDouble() && a.path("pregnant").asBoolean(false) == b.path("pregnant").asBoolean(false);
    }

    private void validateRange(String entry, String unit, ObjectNode r) {
        JsonNode e = ranges.entry(entry);
        if (e == null) throw ApiException.bad("unknown-entry", "unknown knowledge-base entry '" + entry + "'");
        if (unit != null && !unit.isEmpty() && !unit.equals(e.get("unit").asString()))
            throw ApiException.bad("unit-mismatch", entry + ": unit " + unit + " ≠ knowledge-base unit " + e.get("unit").asString() + " (convert before import)");
        if (r.get("ageFrom").asDouble() >= r.get("ageTo").asDouble()) throw ApiException.bad("age", entry + ": ageFrom must be < ageTo");
        if (!List.of("any", "male", "female").contains(r.get("sex").asString())) throw ApiException.bad("sex", entry + ": sex must be any, male or female");
        if (!r.has("low") && !r.has("high")) throw ApiException.bad("limits", entry + ": low and/or high required");
        if (r.has("low") && r.has("high") && r.get("low").decimalValue().compareTo(r.get("high").decimalValue()) >= 0) throw ApiException.bad("limits", entry + ": low must be < high");
    }

    private ObjectNode create(String tenant, String format, String source, Map<String, ArrayNode> entries, String actor) {
        int version = store.list(tenant, KIND).stream().mapToInt(o -> o.path("version").asInt()).max().orElse(0) + 1;
        String id = "ivo-" + version;
        ObjectNode o = Json.obj().put("id", id).put("version", version).put("format", format).put("source", source.isBlank() ? null : source)
                .put("status", "DRAFT").put("importedBy", actor).put("importedAt", Instant.now().toString());
        ObjectNode es = o.putObject("entries");
        ArrayNode parts = o.putArray("partitions");
        entries.forEach((k, v) -> {
            es.set(k, v);
            for (int i = 0; i < v.size(); i++) {
                JsonNode r = v.get(i);
                parts.addObject().put("entry", k).put("index", i).put("partition", partitionText(r)).put("verified", false);
            }
        });
        o.putArray("history").addObject().put("status", "DRAFT").put("by", actor).put("at", Instant.now().toString());
        store.put(tenant, KIND, id, o, "DRAFT", null);
        audit.record(tenant, actor, "interval-overlay.import", KIND + "/" + id, Map.of("format", format, "entries", entries.keySet(), "partitions", parts.size()));
        return o;
    }

    static String partitionText(JsonNode r) {
        return (r.path("pregnant").asBoolean(false) ? "pregnancy T" + r.path("trimester").asInt() + ", " : "") + r.path("sex").asString("any") + ", age "
                + r.path("ageFrom").asDouble() + "–<" + r.path("ageTo").asDouble() + " y";
    }

    // ------------------------------------------------------------------------------------------ life cycle
    /** EP28 transference verification of one partition (entry + index) with ≥ 20 local reference results. */
    public ObjectNode verify(String tenant, String id, String entry, int index, List<BigDecimal> results, String actor) {
        Current.require(GOVERNANCE);
        ObjectNode o = get(tenant, id);
        if (!List.of("DRAFT", "VERIFIED").contains(o.get("status").asString())) throw ApiException.conflict("not-draft", "only DRAFT overlays can be verified (status " + o.get("status").asString() + ")");
        JsonNode r = o.path("entries").path(entry).path(index);
        if (r.isMissingNode()) throw ApiException.notFound("partition " + entry + "#" + index);
        ObjectNode report = ep28(r.hasNonNull("low") ? r.get("low").decimalValue() : null, r.hasNonNull("high") ? r.get("high").decimalValue() : null, results);
        report.put("entry", entry).put("index", index).put("partition", partitionText(r)).put("by", actor).put("at", Instant.now().toString());
        o.withArray("verification").add(report);
        for (JsonNode p : o.withArray("partitions")) if (p.get("entry").asString().equals(entry) && p.get("index").asInt() == index) ((ObjectNode) p).put("verified", report.get("pass").asBoolean());
        boolean all = true;
        for (JsonNode p : o.withArray("partitions")) all &= p.path("verified").asBoolean(false);
        String status = all ? "VERIFIED" : "DRAFT";
        if (!status.equals(o.get("status").asString())) o.withArray("history").addObject().put("status", status).put("by", actor).put("at", Instant.now().toString());
        o.put("status", status);
        store.put(tenant, KIND, id, o, status, null);
        audit.record(tenant, actor, "interval-overlay.verify", KIND + "/" + id, Map.of("entry", entry, "index", index, "n", results.size(), "outside", report.get("outside").asInt(), "pass", report.get("pass").asBoolean()));
        return report;
    }

    /** Four-eyes approval: REGULATORY / TENANT_ADMIN / PHYSICIAN, approver ≠ importer, overlay VERIFIED. */
    public ObjectNode approve(String tenant, String id, String comment, String actor) {
        Current.require(GOVERNANCE);
        ObjectNode o = get(tenant, id);
        if (!"VERIFIED".equals(o.get("status").asString())) throw ApiException.conflict("not-verified", "every partition needs a passing EP28 verification before approval");
        if (actor.equals(o.get("importedBy").asString())) {
            audit.record(tenant, actor, "interval-overlay.approve.refused", KIND + "/" + id, Map.of("reason", "four-eyes"));
            throw ApiException.forbidden("four-eyes", "four-eyes principle: the approver must not be the importer");
        }
        o.put("status", "APPROVED").put("approvedBy", actor).put("approvedAt", Instant.now().toString()).put("approvalComment", comment == null ? "" : comment);
        o.withArray("history").addObject().put("status", "APPROVED").put("by", actor).put("at", Instant.now().toString());
        store.put(tenant, KIND, id, o, "APPROVED", null);
        audit.record(tenant, actor, "interval-overlay.approve", KIND + "/" + id, Map.of("version", o.get("version").asInt()));
        return o;
    }

    public ObjectNode activate(String tenant, String id, String actor) {
        Current.require(GOVERNANCE);
        ObjectNode o = get(tenant, id);
        if (!"APPROVED".equals(o.get("status").asString())) throw ApiException.conflict("not-approved", "only APPROVED overlays can be activated");
        for (ObjectNode prev : store.listByStatus(tenant, KIND, "ACTIVE")) {
            prev.put("status", "SUPERSEDED").put("supersededBy", id).put("supersededAt", Instant.now().toString());
            prev.withArray("history").addObject().put("status", "SUPERSEDED").put("by", actor).put("at", Instant.now().toString());
            store.put(tenant, KIND, prev.get("id").asString(), prev, "SUPERSEDED", null);
        }
        o.put("status", "ACTIVE").put("activatedBy", actor).put("activatedAt", Instant.now().toString());
        o.withArray("history").addObject().put("status", "ACTIVE").put("by", actor).put("at", Instant.now().toString());
        store.put(tenant, KIND, id, o, "ACTIVE", null);
        activeCache.remove(tenant);
        audit.record(tenant, actor, "interval-overlay.activate", KIND + "/" + id, Map.of("version", o.get("version").asInt()));
        return o;
    }

    /** Withdraws the ACTIVE overlay (defaults apply again). */
    public ObjectNode retire(String tenant, String id, String reason, String actor) {
        Current.require(GOVERNANCE);
        ObjectNode o = get(tenant, id);
        if ("ACTIVE".equals(o.get("status").asString()) || "DRAFT".equals(o.get("status").asString()) || "VERIFIED".equals(o.get("status").asString()) || "APPROVED".equals(o.get("status").asString())) {
            o.put("status", "RETIRED").put("retiredBy", actor).put("retiredReason", reason == null ? "" : reason);
            o.withArray("history").addObject().put("status", "RETIRED").put("by", actor).put("at", Instant.now().toString());
            store.put(tenant, KIND, id, o, "RETIRED", null);
            activeCache.remove(tenant);
            audit.record(tenant, actor, "interval-overlay.retire", KIND + "/" + id, Map.of("reason", reason == null ? "" : reason));
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------ helpers
    private static BigDecimal num(String s, String field, BigDecimal dflt) {
        if (s == null || s.isBlank()) {
            if (dflt != null) return dflt;
            throw ApiException.bad("number", field + " is required");
        }
        try { return new BigDecimal(s.trim()); } catch (NumberFormatException e) { throw ApiException.bad("number", field + " '" + s + "' is not a number"); }
    }

    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (char c : line.toCharArray()) {
            if (c == '"') q = !q;
            else if (c == ',' && !q) { out.add(cur.toString().trim()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString().trim());
        return out;
    }
}
