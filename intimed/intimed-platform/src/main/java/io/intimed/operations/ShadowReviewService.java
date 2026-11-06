package io.intimed.operations;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.terminology.Terminology;
import io.intimed.events.EventBus;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Shadow-mode discrepancy report and clinical sign-off (IM-E04-BL-005). During the shadow run (IM-E04-F05) live readings
 * are filed only into shadow template instances (kind {@code shadow}); clinicians or template owners record the values
 * they entered manually for the same reading ({@code shadow-reference}, encrypted at rest – it is clinical content). The
 * report compares automated vs manual values per template field and device: agreement %, mean / max absolute
 * difference and unit. Sign-off is four-eyes (the signer must not be the configuration creator), requires recorded
 * evidence, and sets {@code shadow.signedOff=true} so {@code Pipeline.shadowActive} stops diverting live data. Reports
 * never contain subject identifiers.
 */
@Service
public class ShadowReviewService {
    public static final String REF_KIND = "shadow-reference";
    static final double DEFAULT_TOLERANCE_PCT = 5.0;
    private final DocumentStore store;
    private final Terminology terms;
    private final AuditService audit;
    private final EventBus events;

    public ShadowReviewService(DocumentStore store, Terminology terms, AuditService audit, EventBus events) {
        this.store = store;
        this.terms = terms;
        this.audit = audit;
        this.events = events;
    }

    private ObjectNode shadowConfig(String tenant, String configId) {
        ObjectNode c = store.require(tenant, "config", configId);
        if (!c.path("shadow").isObject()) throw ApiException.conflict("no-shadow", "configuration has no shadow run");
        return c;
    }

    public List<ObjectNode> instances(String tenant, String configId) {
        shadowConfig(tenant, configId);
        return store.list(tenant, "shadow").stream().filter(i -> configId.equals(i.path("configId").asString())).toList();
    }

    /** Records (or replaces) the manually entered reference value for one shadow instance field. */
    public ObjectNode recordManual(String tenant, String configId, String instanceId, String field, JsonNode value, String unit, String actor) {
        shadowConfig(tenant, configId);
        if (instanceId == null || !instanceId.matches("[A-Za-z0-9._-]{1,128}")) throw ApiException.bad("invalid-instance", "invalid shadow instance id");
        if (field == null || !field.matches("[A-Za-z0-9_.-]{1,64}")) throw ApiException.bad("invalid-field", "invalid field id");
        if (unit != null && unit.length() > 32) throw ApiException.bad("invalid-unit", "unit too long");
        ObjectNode inst = store.get(tenant, "shadow", instanceId).orElseThrow(() -> ApiException.notFound("shadow instance"));
        if (!configId.equals(inst.path("configId").asString())) throw ApiException.bad("wrong-config", "shadow instance belongs to another configuration");
        if (!inst.path("fields").has(field)) throw ApiException.bad("unknown-field", "field " + field + " was not captured in this shadow instance");
        ObjectNode ref = Json.obj();
        if (value == null || value.isNull()) throw ApiException.bad("value-required", "value is required");
        if (value.isNumber()) ref.put("value", value.decimalValue());
        else if (value.isString() && value.asString().matches("-?\\d{1,12}(\\.\\d{1,6})?")) ref.put("value", new BigDecimal(value.asString()));
        else if (value.isString() && value.asString().length() <= 64) ref.put("value", value.asString());
        else throw ApiException.bad("invalid-value", "value must be a number or a short code (≤ 64 characters)");
        String id = Hashing.stableId("shr", instanceId + "/" + field);
        Optional<ObjectNode> prev = store.get(tenant, REF_KIND, id);
        ref.put("id", id).put("configId", configId).put("instanceId", instanceId).put("field", field)
                .put("unit", unit == null || unit.isBlank() ? inst.path("fields").path(field).path("unit").asString("") : unit)
                .put("enteredBy", actor).put("enteredAt", Instant.now().toString());
        prev.ifPresent(p -> ref.put("replaces", p.path("enteredAt").asString()));
        store.put(tenant, REF_KIND, id, ref, "RECORDED", configId);
        audit.record(tenant, actor, prev.isPresent() ? "shadow.reference.replace" : "shadow.reference.record", "config/" + configId,
                Map.of("instance", instanceId, "field", field));
        return ref;
    }

    /** Per-field, per-device discrepancy report. {@code tolerancePct}: relative tolerance for numeric agreement (0–50 %). */
    public ObjectNode report(String tenant, String configId, Double tolerancePct) {
        ObjectNode cfg = shadowConfig(tenant, configId);
        double tol = tolerancePct == null ? DEFAULT_TOLERANCE_PCT : tolerancePct;
        if (tol < 0 || tol > 50) throw ApiException.bad("invalid-tolerance", "tolerancePct must be between 0 and 50");
        List<ObjectNode> refs = store.listByRef(tenant, REF_KIND, configId);
        Map<String, Cell> cells = new LinkedHashMap<>();
        Map<String, ObjectNode> instCache = new LinkedHashMap<>();
        for (ObjectNode r : refs) {
            ObjectNode inst = instCache.computeIfAbsent(r.get("instanceId").asString(), k -> store.get(tenant, "shadow", k).orElse(null));
            String field = r.get("field").asString();
            String device = inst == null ? "unknown" : inst.path("deviceSerial").asString("");
            if (device.isBlank()) device = "unknown";
            JsonNode auto = inst == null ? null : inst.path("fields").path(field);
            Cell c = cells.computeIfAbsent(field + "\u0000" + device, k -> new Cell(field, r.path("unit").asString("")));
            c.device = device;
            c.compare(auto, r, tol, terms);
        }
        ObjectNode out = Json.obj().put("configId", configId).put("name", cfg.path("name").asString()).put("tolerancePct", tol)
                .put("generatedAt", Instant.now().toString());
        JsonNode sh = cfg.get("shadow");
        out.putObject("shadow").put("until", sh.path("until").asString("")).put("signedOff", sh.path("signedOff").asBoolean(false))
                .put("signedOffBy", sh.path("signedOffBy").asString("")).put("signedOffAt", sh.path("signedOffAt").asString(""));
        out.put("shadowInstances", store.list(tenant, "shadow").stream().filter(i -> configId.equals(i.path("configId").asString())).count());
        ArrayNode rows = out.putArray("rows");
        long pairs = 0, agreed = 0;
        for (Cell c : cells.values()) {
            rows.add(c.toJson());
            pairs += c.pairs;
            agreed += c.agreed;
        }
        out.put("pairs", pairs).put("agreed", agreed).put("agreementPct", pairs == 0 ? 0 : RegressionService.round1(agreed * 100.0 / pairs));
        out.put("readyForSignOff", pairs > 0 && agreed * 100.0 / pairs >= 95.0);
        out.put("note", "Automated (shadow) vs manually entered values; agreement within ±" + tol + " % (numeric) or exact match (coded). No subject identifiers.");
        return out;
    }

    /** Four-eyes clinical sign-off: ends the shadow run so live data is filed into the real template instances. */
    public ObjectNode signOff(String tenant, String configId, String comment, Double tolerancePct, String actor) {
        ObjectNode c = shadowConfig(tenant, configId);
        if (c.get("shadow").path("signedOff").asBoolean(false)) throw ApiException.conflict("already-signed-off", "shadow run is already signed off");
        if (actor.equals(c.path("createdBy").asString())) {
            audit.record(tenant, actor, "shadow.signoff.refused", "config/" + configId, Map.of("reason", "four-eyes"));
            throw ApiException.forbidden("four-eyes", "the configuration creator cannot sign off its own shadow run");
        }
        if (comment != null && comment.length() > 1000) throw ApiException.bad("comment-too-long", "comment exceeds 1000 characters");
        ObjectNode rep = report(tenant, configId, tolerancePct);
        if (rep.get("pairs").asLong() == 0) throw ApiException.conflict("no-evidence", "record manual reference values before signing off");
        ObjectNode sh = c.withObject("shadow");
        sh.put("signedOff", true).put("signedOffBy", actor).put("signedOffAt", Instant.now().toString()).put("comment", comment == null ? "" : comment);
        sh.putObject("evidence").put("pairs", rep.get("pairs").asLong()).put("agreementPct", rep.get("agreementPct").asDouble())
                .put("tolerancePct", rep.get("tolerancePct").asDouble()).put("readyForSignOff", rep.get("readyForSignOff").asBoolean());
        store.put(tenant, "config", configId, c, c.get("status").asString(), c.path("modelId").asString(null));
        audit.record(tenant, actor, "shadow.signoff", "config/" + configId,
                Map.of("pairs", rep.get("pairs").asLong(), "agreementPct", rep.get("agreementPct").asDouble()));
        events.publish(tenant, "config.changed", Json.obj().put("config", configId).put("change", "shadow.signed-off"));
        return c;
    }

    /** Aggregation cell: one field × one device. */
    static final class Cell {
        final String field;
        String device = "unknown";
        String unit;
        long pairs, agreed, unitMismatches, missingAutomated;
        final List<Double> diffs = new ArrayList<>();

        Cell(String field, String unit) { this.field = field; this.unit = unit; }

        void compare(JsonNode auto, JsonNode ref, double tolPct, Terminology terms) {
            if (auto == null || auto.isMissingNode() || !auto.has("value")) { missingAutomated++; pairs++; return; }
            pairs++;
            JsonNode mv = ref.get("value");
            JsonNode av = auto.get("value");
            if (av.isNumber() && mv.isNumber()) {
                BigDecimal a = av.decimalValue();
                BigDecimal m = mv.decimalValue();
                String au = auto.path("unit").asString(""), mu = ref.path("unit").asString("");
                if (!mu.isBlank() && !au.isBlank() && !mu.equals(au)) {
                    Optional<BigDecimal> conv = terms.convert(m, mu, au, Map.of());
                    if (conv.isEmpty()) { unitMismatches++; return; }
                    m = conv.get();
                }
                if (!au.isBlank()) unit = au;
                double d = a.subtract(m).abs().doubleValue();
                diffs.add(d);
                double allowed = Math.abs(m.doubleValue()) * tolPct / 100.0;
                if (d <= allowed + 1e-9) agreed++;
            } else if (av.asString().equalsIgnoreCase(mv.asString())) {
                agreed++;
            }
        }

        static double round4(double v) { return Math.round(v * 10000.0) / 10000.0; }

        ObjectNode toJson() {
            ObjectNode o = Json.obj().put("field", field).put("device", device).put("unit", unit).put("pairs", pairs).put("agreed", agreed)
                    .put("agreementPct", pairs == 0 ? 0 : RegressionService.round1(agreed * 100.0 / pairs))
                    .put("unitMismatches", unitMismatches).put("missingAutomated", missingAutomated);
            if (!diffs.isEmpty()) {
                o.put("meanAbsDiff", round4(diffs.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                o.put("maxAbsDiff", round4(diffs.stream().mapToDouble(Double::doubleValue).max().orElse(0)));
            }
            return o;
        }
    }
}
