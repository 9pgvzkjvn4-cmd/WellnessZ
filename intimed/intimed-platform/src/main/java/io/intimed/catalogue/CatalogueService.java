package io.intimed.catalogue;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Universal device catalogue (IM-E02): seeded with the 225-device worldwide inventory in 24 families and the 132 FDA
 * product codes with approval counts (openFDA snapshot 19 Oct 2026). Global entries are read-only; tenants add
 * acceptance policies, posture records, model requests and reviewed register-feed changes. Connectivity verification
 * state per model × option: CERTIFIED_IN_LAB (a certified driver exists), VENDOR_CONFIRMED (inventory confidence High),
 * COMMUNITY_REPORTED (Medium), UNVERIFIED (Low).
 */
@Service
public class CatalogueService {
    private final List<ObjectNode> devices = new ArrayList<>();
    private final Map<String, ObjectNode> byId = new LinkedHashMap<>();
    private final Map<String, ObjectNode> codes = new LinkedHashMap<>();
    private final List<ObjectNode> families = new ArrayList<>();
    private final DriverRegistry drivers;
    private final DocumentStore store;
    private final AuditService audit;

    public CatalogueService(DriverRegistry drivers, DocumentStore store, AuditService audit) {
        this.drivers = drivers;
        this.store = store;
        this.audit = audit;
        Json.resource("seed/devices.json").forEach(d -> { devices.add((ObjectNode) d); byId.put(d.get("id").asString(), (ObjectNode) d); });
        Json.resource("seed/fda-codes.json").forEach(c -> codes.put(c.get("code").asString(), (ObjectNode) c));
        Json.resource("seed/families.json").forEach(f -> families.add((ObjectNode) f));
    }

    public int size() { return devices.size(); }
    public Map<String, ObjectNode> codes() { return codes; }
    public List<ObjectNode> families() { return families; }

    public Map<String, Object> stats() {
        long k510 = codes.values().stream().mapToLong(c -> num(c, "k510Total")).sum();
        long listings = codes.values().stream().mapToLong(c -> num(c, "listings")).sum();
        long pma = codes.values().stream().mapToLong(c -> num(c, "pma")).sum();
        return Map.of("devices", devices.size(), "families", families.size(), "fdaCodes", codes.size(), "k510", k510, "listings", listings, "pma", pma,
                "snapshot", "openFDA 19 Oct 2026 (seed)");
    }

    private static long num(JsonNode c, String f) {
        try { return Long.parseLong(c.path(f).asString("0").replaceAll("[^0-9]", "").isEmpty() ? "0" : c.path(f).asString().replaceAll("[^0-9]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    public List<ObjectNode> search(String tenant, String q, String family, String option, String setting, String code, int limit) {
        String needle = q == null ? "" : q.toLowerCase(Locale.ROOT).trim();
        ObjectNode acceptance = acceptance(tenant);
        return devices.stream().filter(d -> needle.isEmpty() || (d.get("id").asString() + " " + d.get("model").asString() + " " + d.get("manufacturer").asString()
                        + " " + d.get("family").asString() + " " + d.get("fdaCodes").toString() + " " + d.get("protocol").asString()).toLowerCase(Locale.ROOT).contains(needle))
                .filter(d -> family == null || family.isBlank() || family.equals(d.get("family").asString()))
                .filter(d -> option == null || option.isBlank() || d.get("options").toString().contains("\"" + option + "\""))
                .filter(d -> setting == null || setting.isBlank() || d.get("settings").toString().contains(setting))
                .filter(d -> code == null || code.isBlank() || d.get("fdaCodes").toString().contains(code.toUpperCase(Locale.ROOT)))
                .limit(Math.max(1, Math.min(limit, 300)))
                .map(d -> summary(tenant, d, acceptance)).collect(Collectors.toList());
    }

    private ObjectNode summary(String tenant, ObjectNode d, ObjectNode acceptance) {
        ObjectNode s = (ObjectNode) d.deepCopy();
        List<DriverPackage> ds = drivers.forModel(tenant, d.get("id").asString());
        s.put("certifiedDrivers", ds.size());
        s.put("accepted", accepted(acceptance, d));
        return s;
    }

    public ObjectNode model(String tenant, String id) {
        ObjectNode d = byId.get(id);
        if (d == null) d = store.get(tenant, "catalogue-model", id).orElseThrow(() -> ApiException.notFound("model " + id));
        ObjectNode m = (ObjectNode) d.deepCopy();
        List<DriverPackage> ds = drivers.forModel(tenant, id);
        ArrayNode drv = m.putArray("drivers");
        for (DriverPackage p : ds) {
            drv.addObject().put("ref", p.ref()).put("name", p.name()).put("channel", p.channel()).put("signature", p.signatureStatus())
                    .put("publisher", p.publisher()).set("options", Json.tree(p.options()));
            ((ObjectNode) drv.get(drv.size() - 1)).set("templates", Json.tree(new ArrayList<>(p.bindings().propertyNames())));
        }
        ArrayNode matrix = m.putArray("connectivity");
        for (JsonNode o : d.get("options")) {
            String opt = o.asString();
            boolean certified = ds.stream().anyMatch(p -> p.options().contains(opt));
            String conf = d.path("confidence").asString("Low");
            String state = certified ? "CERTIFIED_IN_LAB" : switch (conf) { case "High" -> "VENDOR_CONFIRMED"; case "Medium" -> "COMMUNITY_REPORTED"; default -> "UNVERIFIED"; };
            matrix.addObject().put("option", opt).put("state", state).put("confidence", certified ? "HIGH" : conf.toUpperCase(Locale.ROOT))
                    .put("basis", certified ? "Certified driver package with golden sessions and harness evidence" : "Inventory research: " + d.get("protocol").asString());
        }
        ArrayNode cs = m.putArray("fda");
        for (JsonNode c : d.get("fdaCodes")) {
            ObjectNode fc = codes.get(c.asString());
            if (fc != null) cs.add(fc);
        }
        m.set("posture", posture(tenant, id));
        m.put("accepted", accepted(acceptance(tenant), d));
        m.set("recalls", Json.tree(store.listByRef(tenant, "recall", id)));
        return m;
    }

    public ObjectNode requireModel(String id) {
        ObjectNode d = byId.get(id);
        if (d == null) throw ApiException.notFound("model " + id);
        return d;
    }

    // ---------------------------------------------------------------- posture (SC-07, IM-E03-BL-012 / IM-E06-BL-006)
    public ObjectNode posture(String tenant, String modelId) {
        return store.get(tenant, "posture", modelId).orElseGet(() -> Json.obj().put("modelId", modelId).put("mds2", "MISSING").put("sbom", "MISSING")
                .put("eol", (String) null).put("status", "UNASSESSED"));
    }

    public ObjectNode recordPosture(String tenant, String modelId, JsonNode body, String actor) {
        requireModel(modelId);
        ObjectNode p = Json.obj().put("modelId", modelId).put("mds2", body.path("mds2").asString("MISSING")).put("sbom", body.path("sbom").asString("MISSING"))
                .put("eol", body.path("eol").asString(null)).put("knownVulnerabilities", body.path("knownVulnerabilities").asInt(0))
                .put("isolation", body.path("isolation").asString("ZONED")).put("assessedBy", actor).put("assessedAt", Instant.now().toString());
        boolean ok = "RECEIVED".equals(p.get("mds2").asString()) && "RECEIVED".equals(p.get("sbom").asString())
                && (p.get("eol").isNull() || Instant.parse(p.get("eol").asString() + "T00:00:00Z").isAfter(Instant.now())) && p.get("knownVulnerabilities").asInt() == 0;
        p.put("status", ok ? "ACCEPTABLE" : "REQUIRES_EXCEPTION");
        store.put(tenant, "posture", modelId, p, p.get("status").asString(), modelId);
        audit.record(tenant, actor, "posture.record", "model/" + modelId, Map.of("status", p.get("status").asString()));
        return p;
    }

    // ---------------------------------------------------------------- tenant acceptance policy
    public ObjectNode acceptance(String tenant) {
        return store.get(tenant, "acceptance", "policy").orElseGet(() -> {
            ObjectNode a = Json.obj();
            a.putArray("blockedOptions");
            a.putArray("blockedModels");
            a.putArray("allowedFamilies");
            return a;
        });
    }

    public ObjectNode setAcceptance(String tenant, JsonNode body, String actor) {
        ObjectNode a = Json.obj();
        a.set("blockedOptions", body.path("blockedOptions").isArray() ? body.get("blockedOptions") : Json.arr());
        a.set("blockedModels", body.path("blockedModels").isArray() ? body.get("blockedModels") : Json.arr());
        a.set("allowedFamilies", body.path("allowedFamilies").isArray() ? body.get("allowedFamilies") : Json.arr());
        store.put(tenant, "acceptance", "policy", a, "ACTIVE", null);
        audit.record(tenant, actor, "catalogue.acceptance", "acceptance/policy", a);
        return a;
    }

    public static boolean accepted(ObjectNode acceptance, JsonNode d) {
        for (JsonNode b : acceptance.path("blockedModels")) if (b.asString().equals(d.get("id").asString())) return false;
        JsonNode fam = acceptance.path("allowedFamilies");
        if (fam.isArray() && !fam.isEmpty()) {
            boolean ok = false;
            for (JsonNode f : fam) ok |= f.asString().equals(d.get("family").asString());
            if (!ok) return false;
        }
        return true;
    }

    public boolean optionAccepted(String tenant, String option) {
        for (JsonNode b : acceptance(tenant).path("blockedOptions")) if (b.asString().equals(option)) return false;
        return true;
    }

    // ---------------------------------------------------------------- request-a-model workflow
    public ObjectNode requestModel(String tenant, JsonNode body, String actor) {
        String id = Hashing.id("req");
        ObjectNode r = Json.obj().put("id", id).put("manufacturer", body.path("manufacturer").asString()).put("model", body.path("model").asString())
                .put("udi", body.path("udi").asString("")).put("option", body.path("option").asString("")).put("reason", body.path("reason").asString(""))
                .put("requestedBy", actor).put("requestedAt", Instant.now().toString()).put("status", "SUBMITTED");
        if (r.get("manufacturer").asString().isBlank() || r.get("model").asString().isBlank()) throw ApiException.bad("invalid-request", "manufacturer and model are required");
        store.put(tenant, "model-request", id, r, "SUBMITTED", null);
        audit.record(tenant, actor, "catalogue.request", "model-request/" + id, Map.of("manufacturer", r.get("manufacturer").asString(), "model", r.get("model").asString()));
        return r;
    }

    public List<ObjectNode> requests(String tenant) { return store.list(tenant, "model-request"); }

    // ---------------------------------------------------------------- recalls & safety communications (IM-E02-BL-004)
    public ObjectNode addRecall(String tenant, String modelId, JsonNode body, String source, String actor) {
        requireModel(modelId);
        String id = Hashing.id("rcl");
        ObjectNode r = Json.obj().put("id", id).put("modelId", modelId).put("title", body.path("title").asString()).put("classification", body.path("classification").asString(""))
                .put("source", source).put("reference", body.path("reference").asString("")).put("publishedAt", body.path("publishedAt").asString(Instant.now().toString()))
                .put("status", "OPEN").put("addedBy", actor);
        r.putArray("acknowledgements");
        store.put(tenant, "recall", id, r, "OPEN", modelId);
        audit.record(tenant, actor, "catalogue.recall.add", "model/" + modelId, Map.of("source", source));
        return r;
    }

    public ObjectNode acknowledgeRecall(String tenant, String recallId, String note, String actor) {
        ObjectNode r = store.require(tenant, "recall", recallId);
        r.withArray("acknowledgements").addObject().put("by", actor).put("at", Instant.now().toString()).put("note", note == null ? "" : note);
        r.put("status", "ACKNOWLEDGED");
        store.put(tenant, "recall", recallId, r, "ACKNOWLEDGED", r.get("modelId").asString());
        audit.record(tenant, actor, "catalogue.recall.ack", "recall/" + recallId, null);
        return r;
    }

    public List<ObjectNode> recalls(String tenant) { return store.list(tenant, "recall"); }

    /** Coverage analytics (IM-E02-BL-007 local subset). */
    public Map<String, Object> coverage(String tenant) {
        Map<String, Long> byFamily = devices.stream().collect(Collectors.groupingBy(d -> d.get("family").asString(), LinkedHashMap::new, Collectors.counting()));
        Map<String, Long> byOption = new LinkedHashMap<>();
        devices.forEach(d -> d.get("options").forEach(o -> byOption.merge(o.asString(), 1L, Long::sum)));
        long certifiedModels = devices.stream().filter(d -> !drivers.forModel(tenant, d.get("id").asString()).isEmpty()).count();
        return Map.of("byFamily", byFamily, "byOption", byOption, "certifiedModels", certifiedModels, "requests", store.count(tenant, "model-request"));
    }
}
