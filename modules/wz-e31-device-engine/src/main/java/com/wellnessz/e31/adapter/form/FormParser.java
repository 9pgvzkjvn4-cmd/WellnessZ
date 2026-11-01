package com.wellnessz.e31.adapter.form;

import java.time.Instant;
import java.util.List;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;
import com.wellnessz.e31.support.TimeParsing;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code FORM}: values confirmed by the clinician – image/OCR-verified readings of display-only devices (P9) and manual
 * entry with attestation (P10). Body = the fall-back envelope {@code {employeeId, capturedAt, operator, values{key → value}}}.
 * Selectors are value keys (default: the field key).
 */
public class FormParser implements RecordParser {

    private final JsonMapper json;

    public FormParser(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String parseType() {
        return "FORM";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        JsonNode root;
        try {
            root = json.readTree(ctx.body());
        } catch (JacksonException e) {
            throw EngineException.parse("Fall-back payload is not valid JSON");
        }
        JsonNode values = root == null ? null : root.get("values");
        if (values == null || !values.isObject() || values.isEmpty()) {
            throw EngineException.parse("Fall-back payload without values");
        }
        RecordAssembler.SelectorSource src = selector -> {
            JsonNode v = values.get(selector);
            return v == null || v.isNull() ? null : v.isValueNode() ? v.asString() : json.writeValueAsString(v);
        };
        Instant at = root.hasNonNull("capturedAt") ? TimeParsing.iso(root.get("capturedAt").asString(), ctx.zone()) : ctx.receivedAt();
        ParsedRecord p = RecordAssembler.assemble(0, ctx.pkg(), src, ctx.zone(), at);
        if (p.values().isEmpty()) {
            throw EngineException.parse("None of the submitted values is known to the form");
        }
        ParsedRecord.Identity id = p.identity();
        if (root.hasNonNull("employeeId")) {
            id = id.withEmployee(root.get("employeeId").asString());
        }
        id = id.withEnvelope(null, root.hasNonNull("sessionId") ? root.get("sessionId").asString() : null, null,
                root.hasNonNull("operator") ? root.get("operator").asString() : null, at);
        return List.of(new ParsedRecord(0, p.values(), id, p.technical(), p.notes()));
    }
}
