package io.intimed.catalogue;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Register feeds (IM-E02-F02 / IM-E02-BL-003): openFDA 510(k), registration &amp; listing and recall endpoints with change
 * detection against the catalogue snapshot; every change is queued for reviewer approval before it becomes visible.
 * Disabled by default ({@code intimed.feeds.openfda.enabled}); egress is restricted to {@code api.fda.gov}.
 * AccessGUDID and EUDAMED connectors ({@link UdiFeedService}) feed the same review queue (type {@code UDI_RECORD}).
 */
@Service
public class RegisterFeedService {
    public static final String HOST = "api.fda.gov";
    private final CatalogueService catalogue;
    private final DocumentStore store;
    private final AuditService audit;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public RegisterFeedService(CatalogueService catalogue, DocumentStore store, AuditService audit,
                               @Value("${intimed.feeds.openfda.enabled:false}") boolean enabled) {
        this.catalogue = catalogue;
        this.store = store;
        this.audit = audit;
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    /** Checks one FDA product code: 510(k) total and recent recalls; queues detected changes for review. */
    public Map<String, Object> refresh(String tenant, String code, String actor) {
        if (!enabled) throw ApiException.conflict("feed-disabled", "openFDA feed disabled (set intimed.feeds.openfda.enabled=true; requires internet egress to " + HOST + ")");
        ObjectNode seed = catalogue.codes().get(code);
        if (seed == null) throw ApiException.notFound("FDA product code " + code);
        int queued = 0;
        JsonNode k = get("/device/510k.json?search=" + enc("product_code:" + code) + "&limit=1");
        long total = k.at("/meta/results/total").asLong(-1);
        String digits = seed.path("k510Total").asString("0").replaceAll("[^0-9]", "");
        long seedTotal = digits.isEmpty() ? 0 : Long.parseLong(digits);
        if (total >= 0 && total != seedTotal) { queue(tenant, code, "K510_COUNT", Json.obj().put("from", seedTotal).put("to", total)); queued++; }
        JsonNode r = get("/device/recall.json?search=" + enc("product_code:" + code) + "&limit=5&sort=event_date_initiated:desc");
        for (JsonNode rec : r.path("results")) {
            ObjectNode c = Json.obj().put("title", rec.path("product_description").asString("").replaceAll("\\s+", " "))
                    .put("classification", rec.path("root_cause_description").asString("")).put("reference", rec.path("res_event_number").asString(""))
                    .put("publishedAt", rec.path("event_date_initiated").asString(""));
            String key = code + "/" + c.get("reference").asString();
            if (store.listByRef(tenant, "feed-change", key).isEmpty()) { queue(tenant, key, "RECALL", c); queued++; }
        }
        audit.record(tenant, actor, "feed.openfda.refresh", "fda/" + code, Map.of("queued", queued));
        return Map.of("code", code, "k510Total", total, "queued", queued);
    }

    private void queue(String tenant, String ref, String type, ObjectNode change) {
        String id = Hashing.id("chg");
        ObjectNode c = Json.obj().put("id", id).put("type", type).put("ref", ref).put("source", "openFDA").put("detectedAt", Instant.now().toString()).put("status", "PENDING_REVIEW");
        c.set("change", change);
        store.put(tenant, "feed-change", id, c, "PENDING_REVIEW", ref);
    }

    public List<ObjectNode> changes(String tenant) { return store.list(tenant, "feed-change"); }

    /** Reviewer approval – the change becomes visible (recall notices are attached to every catalogue model with that code). */
    public ObjectNode review(String tenant, String id, boolean approve, String actor) {
        ObjectNode c = store.require(tenant, "feed-change", id);
        if (!"PENDING_REVIEW".equals(c.get("status").asString())) throw ApiException.conflict("reviewed", "already reviewed");
        c.put("status", approve ? "APPROVED" : "REJECTED").put("reviewedBy", actor).put("reviewedAt", Instant.now().toString());
        store.put(tenant, "feed-change", id, c, c.get("status").asString(), c.get("ref").asString());
        if (approve && "RECALL".equals(c.get("type").asString())) {
            String code = c.get("ref").asString().split("/")[0];
            catalogue.search(tenant, null, null, null, null, code, 300).forEach(m -> catalogue.addRecall(tenant, m.get("id").asString(), c.get("change"), "openFDA", actor));
        }
        if (approve && "UDI_RECORD".equals(c.get("type").asString())) catalogue.applyUdiRecord(tenant, c.get("change"), actor);
        audit.record(tenant, actor, "feed.review", "feed-change/" + id, Map.of("approved", approve));
        return c;
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("https://" + HOST + path)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) return Json.obj();
            if (r.statusCode() != 200) throw ApiException.conflict("feed-error", "openFDA HTTP " + r.statusCode());
            return Json.parse(r.body());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.conflict("feed-unreachable", "openFDA unreachable: " + e.getClass().getSimpleName());
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
