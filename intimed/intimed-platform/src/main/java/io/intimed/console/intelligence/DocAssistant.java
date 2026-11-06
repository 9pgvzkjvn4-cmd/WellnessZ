package io.intimed.console.intelligence;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.secrets.SecretScanner;
import io.intimed.store.DocumentStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Documentation assistant (IM-E03-F04 / IM-E03-BL-006, local subset): tenant-scoped retrieval over uploaded interface
 * specifications (BM25 ranking over 600-character chunks). Every suggestion cites the document and chunk, is labelled
 * UNVERIFIED until an exchange test passes, and never sets a value (ADR-03). The LLM explanation layer behind the policy
 * gateway ({@link ExplanationService}) grounds on exactly these retrieved passages.
 */
@Service
public class DocAssistant {
    private final DocumentStore store;
    private final AuditService audit;

    public DocAssistant(DocumentStore store, AuditService audit) {
        this.store = store;
        this.audit = audit;
    }

    public ObjectNode upload(String tenant, String name, String text, String actor) {
        if (text == null || text.isBlank() || text.length() > 2_000_000) throw ApiException.bad("invalid-document", "document empty or larger than 2 MB");
        if (!SecretScanner.scan(text).isEmpty()) throw ApiException.unprocessable("document-secret", "document contains credentials – redact before upload");
        String id = Hashing.id("spec");
        ArrayNode chunks = Json.arr();
        String[] paras = text.replace("\r", "").split("\n\\s*\n");
        StringBuilder cur = new StringBuilder();
        for (String p : paras) {
            if (cur.length() + p.length() > 600 && cur.length() > 0) { chunks.add(cur.toString().trim()); cur.setLength(0); }
            cur.append(p).append("\n\n");
        }
        if (cur.length() > 0) chunks.add(cur.toString().trim());
        ObjectNode d = Json.obj().put("id", id).put("name", name).put("uploadedBy", actor).put("uploadedAt", Instant.now().toString()).put("chunks", chunks.size());
        d.set("text", chunks);
        store.put(tenant, "spec-doc", id, d, "ACTIVE", null);
        audit.record(tenant, actor, "docassistant.upload", "spec-doc/" + id, Map.of("chunks", chunks.size()));
        return Json.obj().put("id", id).put("name", name).put("chunks", chunks.size());
    }

    public List<ObjectNode> documents(String tenant) {
        return store.list(tenant, "spec-doc").stream().map(d -> { ObjectNode o = (ObjectNode) d.deepCopy(); o.remove("text"); return o; }).toList();
    }

    public List<ObjectNode> ask(String tenant, String query, int k) {
        List<String> terms = tokens(query);
        List<Object[]> scored = new ArrayList<>();
        List<ObjectNode> docs = store.list(tenant, "spec-doc");
        int n = 0;
        double avg = 0;
        Map<String, Integer> df = new HashMap<>();
        for (ObjectNode d : docs) for (var c : d.get("text")) {
            n++;
            List<String> t = tokens(c.asString());
            avg += t.size();
            for (String term : new java.util.HashSet<>(t)) df.merge(term, 1, Integer::sum);
        }
        if (n == 0) return List.of();
        avg /= n;
        for (ObjectNode d : docs) {
            int i = 0;
            for (var c : d.get("text")) {
                List<String> t = tokens(c.asString());
                double s = 0;
                for (String q : terms) {
                    long tf = t.stream().filter(q::equals).count();
                    if (tf == 0) continue;
                    double idf = Math.log(1 + (n - df.getOrDefault(q, 0) + 0.5) / (df.getOrDefault(q, 0) + 0.5));
                    s += idf * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * t.size() / avg));
                }
                if (s > 0) scored.add(new Object[]{s, d, i, c.asString()});
                i++;
            }
        }
        scored.sort(Comparator.comparingDouble(o -> -(double) o[0]));
        List<ObjectNode> out = new ArrayList<>();
        for (Object[] o : scored.subList(0, Math.min(k, scored.size()))) {
            ObjectNode d = (ObjectNode) o[1];
            out.add(Json.obj().put("score", Math.round((double) o[0] * 100) / 100.0).put("document", d.get("name").asString()).put("documentId", d.get("id").asString())
                    .put("chunk", (int) o[2]).put("passage", (String) o[3]).put("status", "UNVERIFIED – suggestion until the exchange test passes")
                    .put("citation", d.get("name").asString() + " §" + ((int) o[2] + 1)));
        }
        return out;
    }

    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).split("[^a-z0-9_.-]+")) if (t.length() > 1) out.add(t);
        return out;
    }
}
