package io.intimed.console;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connectivity option packs P1–P11 (IM-E03-F05 / IM-E03-BL-007): parameter schemas with defaults and validation,
 * prerequisites, guides, security / reliability / effort / latency and option-specific test plan fragments. The wizard
 * renders its step-4 forms from these schemas and the policy engine validates against them.
 */
@Component
public class OptionPacks {
    private final Map<String, ObjectNode> packs = new LinkedHashMap<>();

    public OptionPacks() {
        for (int i = 1; i <= 11; i++) packs.put("P" + i, (ObjectNode) Json.resource("optionpacks/P" + i + ".json"));
    }

    public List<ObjectNode> all() { return new ArrayList<>(packs.values()); }

    public ObjectNode get(String option) {
        ObjectNode p = packs.get(option);
        if (p == null) throw ApiException.bad("unknown-option", "unknown connectivity option " + option);
        return p;
    }

    /** Schema validation of parameter values; returns problems (empty = valid). */
    public List<String> validate(String option, JsonNode params) {
        List<String> out = new ArrayList<>();
        for (JsonNode s : get(option).get("parameters")) {
            String n = s.get("name").asString();
            JsonNode v = params.get(n);
            boolean missing = v == null || v.isNull() || v.asString("").isBlank();
            if (missing) { if (s.path("required").asBoolean() && !"secret".equals(s.get("type").asString())) out.add(n + " is required"); continue; }
            switch (s.get("type").asString()) {
                case "enum" -> {
                    boolean ok = false;
                    for (JsonNode e : s.get("values")) ok |= e.asString().equals(v.asString());
                    if (!ok) out.add(n + " must be one of " + s.get("values"));
                }
                case "int", "number" -> {
                    try {
                        double d = Double.parseDouble(v.asString());
                        if (s.has("min") && d < s.get("min").asDouble() || s.has("max") && d > s.get("max").asDouble()) out.add(n + " out of range " + s.get("min") + "–" + s.get("max"));
                    } catch (NumberFormatException e) { out.add(n + " must be numeric"); }
                }
                case "secret" -> { if (!v.asString().startsWith("secret://")) out.add(n + " must be a vault reference secret://… (SC-05)"); }
                default -> { if (v.asString().length() > 500) out.add(n + " too long"); }
            }
        }
        return out;
    }
}
