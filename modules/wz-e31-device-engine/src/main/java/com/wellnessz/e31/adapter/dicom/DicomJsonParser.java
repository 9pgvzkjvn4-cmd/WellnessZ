package com.wellnessz.e31.adapter.dicom;

import java.util.ArrayList;
import java.util.List;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code DICOM_JSON}: DICOMweb-style instance metadata (DICOM PS3.18 JSON model, {@code {"00100020":{"vr":"LO","Value":[…]}}})
 * posted by the edge; the instance itself is archived through {@code /device-ingest/attachments} (no pixel processing in the
 * core – dcm4che is not used, contract section 0). Accepted bodies: one dataset, an array of datasets, or
 * {@code {"archiveId": …, "datasets": […]}}. Selectors are tags ({@code 00100020}) or tag concatenations
 * ({@code 00080020+00080030}); person names use the {@code Alphabetic} representation; time fractions are dropped.
 */
public class DicomJsonParser implements RecordParser {

    private final JsonMapper json;

    public DicomJsonParser(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String parseType() {
        return "DICOM_JSON";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        List<JsonNode> datasets = datasets(json, ctx.body());
        List<ParsedRecord> out = new ArrayList<>();
        for (JsonNode ds : datasets) {
            if (!ds.isObject() || ds.propertyNames().stream().noneMatch(k -> k.matches("[0-9A-Fa-f]{8}"))) {
                throw EngineException.profile("Not a DICOM JSON dataset");
            }
            if (!ds.has("0020000D") && !ds.has("0020000d")) {
                throw EngineException.profile("DICOM dataset without Study Instance UID");
            }
            RecordAssembler.SelectorSource src = selector -> {
                StringBuilder sb = new StringBuilder();
                for (String tag : selector.split("\\+")) {
                    String v = tag(ds, tag.strip());
                    if (v == null) {
                        return null;
                    }
                    sb.append(v);
                }
                return sb.toString();
            };
            out.add(RecordAssembler.assemble(out.size(), ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
        }
        return out;
    }

    public static List<JsonNode> datasets(JsonMapper json, byte[] body) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw EngineException.parse("DICOM metadata is not valid JSON");
        }
        List<JsonNode> out = new ArrayList<>();
        if (root != null && root.isObject() && root.has("datasets")) {
            root.get("datasets").forEach(out::add);
        } else if (root != null && root.isArray()) {
            root.forEach(out::add);
        } else if (root != null && root.isObject()) {
            out.add(root);
        }
        if (out.isEmpty()) {
            throw EngineException.parse("No DICOM dataset in the body");
        }
        return out;
    }

    /** Archive id of the instance when the body wraps the datasets ({@code archiveId}). */
    public static String archiveId(JsonMapper json, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            return root != null && root.isObject() && root.hasNonNull("archiveId") ? root.get("archiveId").asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    static String tag(JsonNode ds, String tag) {
        JsonNode el = ds.get(tag.toUpperCase(java.util.Locale.ROOT));
        if (el == null) {
            el = ds.get(tag.toLowerCase(java.util.Locale.ROOT));
        }
        if (el == null) {
            return null;
        }
        JsonNode v = el.path("Value");
        if (!v.isArray() || v.isEmpty()) {
            return null;
        }
        JsonNode first = v.get(0);
        String vr = el.path("vr").asString("");
        String s;
        if ("PN".equals(vr) && first.isObject()) {
            s = first.path("Alphabetic").asString(null);
        } else if (first.isValueNode()) {
            s = first.asString();
        } else {
            s = null;
        }
        if (s != null && "TM".equals(vr) && s.contains(".")) {
            s = s.substring(0, s.indexOf('.'));
        }
        return s;
    }
}
