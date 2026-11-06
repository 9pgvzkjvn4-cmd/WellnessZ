package io.intimed.catalogue;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.egress.EgressClient;
import io.intimed.store.DocumentStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * UDI register connectors (IM-E02-BL-003): AccessGUDID (FDA / NLM) device lookup by device identifier and EUDAMED
 * (EU MDR) public device search, alongside the openFDA feed of {@link RegisterFeedService}. Same pattern: the record is
 * normalised, its content hash compared with the last <b>approved</b> snapshot ({@code udi-record}); a difference is queued
 * as a {@code feed-change} (type {@code UDI_RECORD}) in the shared reviewer queue and only an approval by a Device
 * Administrator / Regulatory reviewer updates the catalogue ({@link CatalogueService#applyUdiRecord}).
 *
 * <p><b>Configuration</b> (both connectors disabled by default):
 * <pre>
 * intimed.feeds.gudid.enabled=false
 * intimed.feeds.gudid.base-url=https://accessgudid.nlm.nih.gov        # GET {base}/api/v3/devices/lookup.json?di={DI}
 * intimed.feeds.eudamed.enabled=false
 * intimed.feeds.eudamed.base-url=https://ec.europa.eu/tools/eudamed/api
 * intimed.feeds.eudamed.search-path=/devices/udiDiData?page=0&amp;pageSize=10&amp;iso2Code=en&amp;primaryDi={di}
 * intimed.feeds.eudamed.text-search-path=/devices/udiDiData?page=0&amp;pageSize=10&amp;iso2Code=en&amp;tradeName={q}
 * intimed.feeds.eudamed.results-pointer=/content
 * intimed.feeds.eudamed.fields=di:/primaryDi,tradeName:/tradeName,manufacturer:/manufacturerName,model:/reference,riskClass:/riskClass/code,status:/deviceStatusType/code
 * intimed.feeds.allowed-hosts=api.fda.gov,accessgudid.nlm.nih.gov,ec.europa.eu
 * </pre>
 * The EUDAMED public API is not a contractual, versioned API (the public UI's JSON backend changes without notice), so
 * the connector is driven by configuration: the search path templates, a JSON pointer to the result array and a list of
 * {@code name:/json/pointer} field mappings. {@code di} is mandatory; every other mapped field becomes part of the
 * normalised record and of its content hash. When the EUDAMED shape changes, operators adjust these properties – no
 * code change. Every call goes through {@link EgressClient}: HTTPS only, host allow-list, no redirects, 2 MiB response cap.
 */
@Service
public class UdiFeedService {
    public static final Pattern DI = Pattern.compile("[A-Za-z0-9.+/\\-]{6,40}");
    public static final String GUDID = "AccessGUDID", EUDAMED = "EUDAMED";

    /** Connector settings (constructed from properties, or directly in tests). */
    public record Settings(boolean gudidEnabled, String gudidBaseUrl, boolean eudamedEnabled, String eudamedBaseUrl, String eudamedSearchPath,
                           String eudamedTextSearchPath, String eudamedResultsPointer, String eudamedFields, String allowedHosts, boolean allowLoopbackHttp) {
        public static Settings defaults() {
            return new Settings(false, "https://accessgudid.nlm.nih.gov", false, "https://ec.europa.eu/tools/eudamed/api",
                    "/devices/udiDiData?page=0&pageSize=10&iso2Code=en&primaryDi={di}", "/devices/udiDiData?page=0&pageSize=10&iso2Code=en&tradeName={q}", "/content",
                    "di:/primaryDi,tradeName:/tradeName,manufacturer:/manufacturerName,model:/reference,riskClass:/riskClass/code,status:/deviceStatusType/code",
                    "api.fda.gov,accessgudid.nlm.nih.gov,ec.europa.eu", false);
        }
    }

    private final CatalogueService catalogue;
    private final DocumentStore store;
    private final AuditService audit;
    private final Settings s;
    private final EgressClient http;
    private final Map<String, String> eudamedFields = new LinkedHashMap<>();

    @Autowired
    public UdiFeedService(CatalogueService catalogue, DocumentStore store, AuditService audit,
                          @Value("${intimed.feeds.gudid.enabled:false}") boolean gudidEnabled,
                          @Value("${intimed.feeds.gudid.base-url:https://accessgudid.nlm.nih.gov}") String gudidBaseUrl,
                          @Value("${intimed.feeds.eudamed.enabled:false}") boolean eudamedEnabled,
                          @Value("${intimed.feeds.eudamed.base-url:https://ec.europa.eu/tools/eudamed/api}") String eudamedBaseUrl,
                          @Value("${intimed.feeds.eudamed.search-path}") String searchPath,
                          @Value("${intimed.feeds.eudamed.text-search-path}") String textPath,
                          @Value("${intimed.feeds.eudamed.results-pointer:/content}") String resultsPointer,
                          @Value("${intimed.feeds.eudamed.fields:di:/primaryDi,tradeName:/tradeName,manufacturer:/manufacturerName,model:/reference,riskClass:/riskClass/code,status:/deviceStatusType/code}") String fields,
                          @Value("${intimed.feeds.allowed-hosts:api.fda.gov,accessgudid.nlm.nih.gov,ec.europa.eu}") String allowedHosts,
                          @Value("${intimed.feeds.allow-loopback-http:false}") boolean allowLoopbackHttp) {
        this(catalogue, store, audit, new Settings(gudidEnabled, gudidBaseUrl, eudamedEnabled, eudamedBaseUrl, searchPath, textPath, resultsPointer, fields, allowedHosts, allowLoopbackHttp));
    }

    public UdiFeedService(CatalogueService catalogue, DocumentStore store, AuditService audit, Settings settings) {
        this.catalogue = catalogue;
        this.store = store;
        this.audit = audit;
        this.s = settings;
        this.http = new EgressClient(EgressClient.hosts(settings.allowedHosts()), settings.allowLoopbackHttp(), 2 * 1024 * 1024);
        for (String m : settings.eudamedFields().split(",")) {
            String[] kv = m.trim().split(":", 2);
            if (kv.length == 2 && kv[1].startsWith("/")) eudamedFields.put(kv[0].trim(), kv[1].trim());
        }
        if (!eudamedFields.containsKey("di")) throw new IllegalStateException("intimed.feeds.eudamed.fields must map 'di'");
    }

    public Map<String, Object> status() {
        return Map.of("gudid", Map.of("enabled", s.gudidEnabled(), "host", host(s.gudidBaseUrl())),
                "eudamed", Map.of("enabled", s.eudamedEnabled(), "host", host(s.eudamedBaseUrl()), "fields", eudamedFields.keySet()),
                "allowedHosts", http.allowedHosts());
    }

    private static String host(String url) { try { return java.net.URI.create(url).getHost(); } catch (RuntimeException e) { return ""; } }

    // ---------------------------------------------------------------- AccessGUDID
    /** Looks up one device identifier in AccessGUDID; queues a review item when the record is new or changed. */
    public ObjectNode gudidLookup(String tenant, String di, String modelId, String actor) {
        if (!s.gudidEnabled()) throw ApiException.conflict("feed-disabled", "AccessGUDID feed disabled (set intimed.feeds.gudid.enabled=true; requires egress to " + host(s.gudidBaseUrl()) + ")");
        String d = requireDi(di);
        String model = validModel(tenant, modelId);
        EgressClient.Response r = http.get(s.gudidBaseUrl() + "/api/v3/devices/lookup.json?di=" + enc(d), Map.of());
        if (r.status() == 404 || r.status() == 400) {
            audit.record(tenant, actor, "feed.gudid.lookup", "udi/" + d, Map.of("found", false));
            return Json.obj().put("source", GUDID).put("di", d).put("found", false).put("queued", false);
        }
        if (r.status() != 200) throw ApiException.conflict("feed-error", "AccessGUDID HTTP " + r.status());
        JsonNode dev = parse(r.body()).at("/gudid/device");
        if (dev.isMissingNode() || !dev.isObject()) throw ApiException.conflict("feed-shape", "AccessGUDID response without gudid.device");
        ObjectNode rec = Json.obj().put("di", d).put("brandName", dev.path("brandName").asString("")).put("versionModelNumber", dev.path("versionModelNumber").asString(""))
                .put("catalogNumber", dev.path("catalogNumber").asString("")).put("companyName", dev.path("companyName").asString(""))
                .put("description", dev.path("deviceDescription").asString("")).put("recordStatus", dev.path("deviceRecordStatus").asString(""))
                .put("publishDate", dev.path("devicePublishDate").asString("")).put("mriSafety", dev.path("MRISafetyStatus").asString(""));
        ArrayNode codes = rec.putArray("productCodes");
        for (JsonNode c : items(dev.at("/productCodes/fdaProductCode"))) codes.add(c.path("productCode").asString(""));
        ArrayNode gmdn = rec.putArray("gmdnTerms");
        for (JsonNode g : items(dev.at("/gmdnTerms/gmdn"))) gmdn.add(g.path("gmdnPTName").asString(""));
        ArrayNode ids = rec.putArray("identifiers");
        for (JsonNode i : items(dev.at("/identifiers/identifier"))) ids.addObject().put("deviceId", i.path("deviceId").asString("")).put("type", i.path("deviceIdType").asString(""));
        ObjectNode out = detect(tenant, GUDID, d, model, rec, actor);
        audit.record(tenant, actor, "feed.gudid.lookup", "udi/" + d, Map.of("found", true, "queued", out.get("queued").asBoolean(), "hash", out.get("hash").asString()));
        return out;
    }

    // ---------------------------------------------------------------- EUDAMED
    /** EUDAMED public device search by DI ({@code di}) or trade name ({@code q}); each result goes through change detection. */
    public ObjectNode eudamedSearch(String tenant, String di, String q, String modelId, String actor) {
        if (!s.eudamedEnabled()) throw ApiException.conflict("feed-disabled", "EUDAMED feed disabled (set intimed.feeds.eudamed.enabled=true; requires egress to " + host(s.eudamedBaseUrl()) + ")");
        String model = validModel(tenant, modelId);
        String path;
        if (di != null && !di.isBlank()) path = s.eudamedSearchPath().replace("{di}", enc(requireDi(di)));
        else if (q != null && !q.isBlank()) {
            if (q.length() > 100) throw ApiException.bad("invalid-query", "search text longer than 100 characters");
            path = s.eudamedTextSearchPath().replace("{q}", enc(q.trim()));
        } else throw ApiException.bad("invalid-query", "di or q is required");
        EgressClient.Response r = http.get(s.eudamedBaseUrl() + path, Map.of());
        if (r.status() == 404) return Json.obj().put("source", EUDAMED).put("results", 0).put("queued", 0);
        if (r.status() != 200) throw ApiException.conflict("feed-error", "EUDAMED HTTP " + r.status());
        JsonNode results = parse(r.body()).at(s.eudamedResultsPointer());
        if (!results.isArray()) throw ApiException.conflict("feed-shape", "EUDAMED response has no array at " + s.eudamedResultsPointer() + " – adjust intimed.feeds.eudamed.results-pointer");
        int queued = 0, n = 0;
        ArrayNode items = Json.arr();
        for (JsonNode e : results) {
            if (++n > 25) break;
            ObjectNode rec = Json.obj();
            eudamedFields.forEach((name, ptr) -> {
                JsonNode v = e.at(ptr);
                rec.put(name, v.isMissingNode() || v.isNull() ? "" : v.isValueNode() ? v.asString() : Json.write(v));
            });
            String recDi = rec.get("di").asString();
            if (!DI.matcher(recDi).matches()) continue;
            ObjectNode o = detect(tenant, EUDAMED, recDi, model, rec, actor);
            if (o.get("queued").asBoolean()) queued++;
            items.add(o);
        }
        audit.record(tenant, actor, "feed.eudamed.search", di != null && !di.isBlank() ? "udi/" + di : "eudamed/search", Map.of("results", items.size(), "queued", queued));
        ObjectNode out = Json.obj().put("source", EUDAMED).put("results", items.size()).put("queued", queued);
        out.set("items", items);
        return out;
    }

    // ---------------------------------------------------------------- change detection
    private ObjectNode detect(String tenant, String source, String di, String modelId, ObjectNode rec, String actor) {
        String key = key(source, di);
        String hash = Hashing.sha256(Json.canonical(rec));
        Optional<ObjectNode> baseline = store.get(tenant, "udi-record", key);
        String prevHash = baseline.map(b -> b.path("hash").asString("")).orElse("");
        ObjectNode out = Json.obj().put("source", source).put("di", di).put("found", true).put("hash", hash).put("previousHash", prevHash);
        out.set("record", rec);
        if (hash.equals(prevHash)) return out.put("queued", false).put("change", "UNCHANGED");
        String ref = source.toLowerCase(java.util.Locale.ROOT) + "/" + di;
        for (ObjectNode c : store.listByRef(tenant, "feed-change", ref)) {
            if ("PENDING_REVIEW".equals(c.path("status").asString()) && hash.equals(c.at("/change/hash").asString())) {
                return out.put("queued", false).put("change", "ALREADY_QUEUED").put("changeId", c.get("id").asString());
            }
        }
        String id = Hashing.id("chg");
        ObjectNode change = Json.obj().put("source", source).put("di", di).put("key", key).put("hash", hash).put("previousHash", prevHash).put("modelId", modelId);
        change.set("record", rec);
        baseline.ifPresent(b -> change.set("previous", b.get("record")));
        if (modelId != null) {
            List<String> catalogueCodes = new ArrayList<>();
            catalogue.requireModel(modelId).path("fdaCodes").forEach(c -> catalogueCodes.add(c.asString()));
            boolean match = false;
            for (JsonNode pc : rec.path("productCodes")) match |= catalogueCodes.contains(pc.asString());
            change.put("productCodeMatch", rec.has("productCodes") ? match : null);
        }
        ObjectNode c = Json.obj().put("id", id).put("type", "UDI_RECORD").put("ref", ref).put("source", source).put("detectedAt", Instant.now().toString())
                .put("detectedBy", actor).put("status", "PENDING_REVIEW");
        c.set("change", change);
        store.put(tenant, "feed-change", id, c, "PENDING_REVIEW", ref);
        return out.put("queued", true).put("change", prevHash.isEmpty() ? "NEW" : "CHANGED").put("changeId", id);
    }

    public static String key(String source, String di) { return source.toLowerCase(java.util.Locale.ROOT) + ":" + di; }

    private String validModel(String tenant, String modelId) {
        if (modelId == null || modelId.isBlank()) return null;
        catalogue.requireModel(modelId);
        return modelId;
    }

    private static String requireDi(String di) {
        if (di == null || !DI.matcher(di.trim()).matches()) throw ApiException.bad("invalid-di", "device identifier must be 6–40 characters [A-Za-z0-9.+/-]");
        return di.trim();
    }

    private static JsonNode parse(String body) {
        try { return Json.parse(body); } catch (RuntimeException e) { throw ApiException.conflict("feed-shape", "register answered non-JSON content"); }
    }

    private static List<JsonNode> items(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n == null || n.isMissingNode() || n.isNull()) return out;
        if (n.isArray()) n.forEach(out::add); else out.add(n);
        return out;
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }
}
