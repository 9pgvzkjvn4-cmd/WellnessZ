package io.intimed.clinical;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Healthcare-platform categories: which templates, output profiles, validation rule packs and interpretation policy apply. */
@Service
public class PlatformCategories {
    private final JsonNode all = Json.resource("clinical/platform-categories.json");

    public JsonNode all() { return all; }

    public JsonNode require(String id) {
        for (JsonNode c : all) if (c.get("id").asString().equals(id)) return c;
        throw ApiException.notFound("platform category " + id);
    }

    public List<String> forTemplate(String templateId) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : all) for (JsonNode t : c.path("templates")) if (t.asString().equals(templateId)) out.add(c.get("id").asString());
        return out;
    }

    public boolean has(JsonNode cat, String rule) {
        for (JsonNode r : cat.path("rules")) if (r.asString().equals(rule)) return true;
        return false;
    }
}
