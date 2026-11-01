package io.intimed.engine.driver;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Declarative, signed driver package (architecture §8.3): manifest · transport · grammar · semantics · bindings ·
 * recommendations · tests · docs. Device variety lives here, not in code (ADR-02).
 */
public record DriverPackage(JsonNode json, String channel, String signatureStatus, String publisher) {
    public String packageId() { return json.at("/manifest/packageId").asString(); }
    public String version() { return json.at("/manifest/version").asString(); }
    public String ref() { return packageId() + "@" + version(); }
    public String name() { return json.at("/manifest/name").asString(); }
    public String family() { return json.at("/manifest/family").asString(); }
    public JsonNode manifest() { return json.get("manifest"); }
    public JsonNode grammar() { return json.get("grammar"); }
    public JsonNode semantics() { return json.get("semantics"); }
    public JsonNode bindings() { return json.path("bindings"); }
    public JsonNode recommendations() { return json.path("recommendations"); }
    public JsonNode tests() { return json.path("tests"); }
    public JsonNode transport(String option) { return json.path("transport").path(option); }

    public List<String> options() {
        List<String> o = new ArrayList<>();
        json.at("/manifest/options").forEach(n -> o.add(n.asString()));
        return o;
    }

    public List<String> models() {
        List<String> o = new ArrayList<>();
        json.at("/manifest/models").forEach(n -> o.add(n.asString()));
        return o;
    }

    public JsonNode semantic(String key) {
        for (JsonNode s : semantics()) if (s.get("key").asString().equals(key)) return s;
        return null;
    }

    public Map<String, JsonNode> semanticsByKey() {
        Map<String, JsonNode> m = new HashMap<>();
        semantics().forEach(s -> m.put(s.get("key").asString(), s));
        return m;
    }

    public boolean restricted() { return json.at("/manifest/restricted").asBoolean(false); }
}
