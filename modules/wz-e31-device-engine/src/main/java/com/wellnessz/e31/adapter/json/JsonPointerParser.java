package com.wellnessz.e31.adapter.json;

import java.util.ArrayList;
import java.util.List;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonPointer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code JSON_POINTER}: vendor-cloud webhooks and polled REST results (P6). Selectors are RFC 6901 pointers; arrays and
 * objects selected for STRING fields are kept as compact JSON (e.g. fit-test exercises). A top-level array is one record
 * per element.
 */
public class JsonPointerParser implements RecordParser {

    private final JsonMapper json;

    public JsonPointerParser(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String parseType() {
        return "JSON_POINTER";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        JsonNode root;
        try {
            root = json.readTree(ctx.body());
        } catch (JacksonException e) {
            throw EngineException.parse("Vendor payload is not valid JSON");
        }
        if (root == null || !(root.isObject() || root.isArray())) {
            throw EngineException.parse("Vendor payload must be a JSON object or array");
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else {
            items.add(root);
        }
        List<ParsedRecord> out = new ArrayList<>();
        for (JsonNode item : items) {
            RecordAssembler.SelectorSource src = selector -> {
                JsonNode n;
                try {
                    n = item.at(JsonPointer.compile(selector));
                } catch (IllegalArgumentException e) {
                    throw new EngineException("parse_failed", "Invalid JSON pointer in the driver package", true);
                }
                if (n == null || n.isMissingNode() || n.isNull()) {
                    return null;
                }
                return n.isValueNode() ? n.asString() : json.writeValueAsString(n);
            };
            out.add(RecordAssembler.assemble(out.size(), ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
        }
        return out;
    }
}
