package io.intimed.templates;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.store.DocumentStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Host-agnostic Template Definitions (IM-E05-F01, ADR-05): the 22 reference templates TPL-01…TPL-22 of the solution
 * architecture (§9) plus tenant templates imported from FHIR Questionnaires or host schemas. Versioned: a new version of
 * a template triggers regression of the bound published configurations.
 */
@Service
public class TemplateRegistry {
    private final Map<String, ObjectNode> reference = new LinkedHashMap<>();
    private final DocumentStore store;
    private final AuditService audit;

    public TemplateRegistry(DocumentStore store, AuditService audit) throws IOException {
        this.store = store;
        this.audit = audit;
        Resource[] rs = new PathMatchingResourcePatternResolver().getResources("classpath*:templates/TPL-*.json");
        java.util.Arrays.sort(rs, java.util.Comparator.comparing(Resource::getFilename));
        for (Resource r : rs) {
            ObjectNode t = (ObjectNode) Json.parse(r.getContentAsString(StandardCharsets.UTF_8));
            t.put("origin", "REFERENCE");
            reference.put(t.get("id").asString(), t);
        }
    }

    public List<ObjectNode> all(String tenant) {
        List<ObjectNode> out = new ArrayList<>(reference.values());
        out.addAll(store.list(tenant, "template"));
        return out;
    }

    public Optional<ObjectNode> find(String tenant, String id) {
        if (reference.containsKey(id)) return Optional.of(reference.get(id));
        return store.get(tenant, "template", id);
    }

    public ObjectNode require(String tenant, String id) { return find(tenant, id).orElseThrow(() -> ApiException.notFound("template " + id)); }

    public static List<JsonNode> fields(JsonNode tpl) {
        List<JsonNode> out = new ArrayList<>();
        tpl.path("sections").forEach(s -> s.path("fields").forEach(out::add));
        return out;
    }

    public static JsonNode field(JsonNode tpl, String id) {
        for (JsonNode f : fields(tpl)) if (f.get("id").asString().equals(id)) return f;
        return null;
    }

    /**
     * Import a FHIR R4 Questionnaire as a Template Definition: items of type decimal/integer/quantity/string/choice become
     * fields; item.code → semantics; questionnaire-unit / unitOption extension → unit; required → mandatory.
     */
    public ObjectNode importQuestionnaire(String tenant, JsonNode q, String actor) {
        if (!"Questionnaire".equals(q.path("resourceType").asString(""))) throw ApiException.bad("not-questionnaire", "resourceType must be Questionnaire");
        String id = "HOST-" + q.path("id").asString("tpl").replaceAll("[^A-Za-z0-9-]", "").toUpperCase();
        ObjectNode t = Json.obj().put("id", id).put("version", q.path("version").asString("1.0.0")).put("name", q.path("title").asString(id))
                .put("origin", "FHIR_QUESTIONNAIRE").put("captureMode", "SPOT").put("association", "WORKLIST");
        t.set("settings", Json.arr().add("HOST"));
        t.set("adapters", Json.arr().add("FHIR").add("REST"));
        ArrayNode sections = t.putArray("sections");
        ObjectNode sec = sections.addObject().put("id", "main").put("label", t.get("name").asString());
        ArrayNode fs = sec.putArray("fields");
        walk(q.path("item"), fs);
        if (fs.isEmpty()) throw ApiException.unprocessable("no-fields", "questionnaire has no capturable items");
        store.put(tenant, "template", id, t, "ACTIVE", null);
        audit.record(tenant, actor, "template.import", "template/" + id, Map.of("fields", fs.size()));
        return t;
    }

    private static void walk(JsonNode items, ArrayNode fs) {
        for (JsonNode it : items) {
            String type = it.path("type").asString("");
            if (type.equals("group")) { walk(it.path("item"), fs); continue; }
            if (!List.of("decimal", "integer", "quantity", "string", "choice", "boolean").contains(type)) continue;
            ObjectNode f = fs.addObject().put("id", it.path("linkId").asString().replaceAll("[^A-Za-z0-9_.-]", "_"))
                    .put("label", it.path("text").asString(it.path("linkId").asString()))
                    .put("type", List.of("decimal", "integer", "quantity").contains(type) ? "quantity" : "text")
                    .put("mandatory", it.path("required").asBoolean(false));
            JsonNode code = it.path("code").path(0);
            if (!code.isMissingNode()) {
                ObjectNode c = f.putObject("code");
                String sys = code.path("system").asString("");
                c.put(sys.contains("loinc") ? "loinc" : sys.contains("11073") ? "mdc" : "code", code.path("code").asString());
                // code from a system IntiMed does not know: keep the system for the terminology adapter (IM-E12-BL-010)
                if (!sys.contains("loinc") && !sys.contains("11073") && !sys.isBlank()) c.put("system", sys);            }
            for (JsonNode ext : it.path("extension")) {
                String url = ext.path("url").asString("");
                if (url.endsWith("questionnaire-unit") || url.endsWith("questionnaire-unitOption")) f.put("unit", ext.path("valueCoding").path("code").asString());
                if (url.endsWith("minValue")) f.putObject("range").put("min", ext.path("valueDecimal").asDouble(ext.path("valueInteger").asDouble()));
            }
        }
    }
}
