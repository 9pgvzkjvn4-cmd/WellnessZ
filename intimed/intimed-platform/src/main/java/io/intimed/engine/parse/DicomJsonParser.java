package io.intimed.engine.parse;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * P7 DICOM via DICOMweb JSON (PS3.18 F.2) – study/series attributes and RDSR / SR numeric content delivered by the
 * DICOM router (C-STORE SCP / STOW-RS). Selector = 8-hex-digit tag, or {@code SR:<code value>} for TID 1500/10011
 * numeric content items (code → MeasuredValueSequence).
 */
public class DicomJsonParser implements PayloadParser {
    @Override public String type() { return "DICOM_JSON"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        JsonNode ds;
        try { ds = Json.parse(payload); } catch (RuntimeException e) { throw new ParseException("not DICOM JSON"); }
        if (ds.isArray()) ds = ds.get(0);
        if (ds == null || !ds.isObject() || !ds.has("0020000D")) throw new ParseException("DICOM JSON without Study Instance UID");
        Parsed p = new Parsed();
        for (var e : g.path("identity").properties()) p.id(e.getKey(), tag(ds, e.getValue().asString()));
        for (JsonNode f : g.path("fields")) {
            String sel = f.get("tag").asString();
            if (sel.startsWith("SR:")) {
                String[] vu = sr(ds.get("0040A730"), sel.substring(3));
                if (vu != null) p.val(f.get("key").asString(), vu[0], vu[1] == null ? f.path("unit").asString(null) : vu[1]);
            } else {
                p.val(f.get("key").asString(), tag(ds, sel), f.path("unit").asString(null));
            }
        }
        if (p.values.isEmpty()) throw new ParseException("no mapped DICOM attributes");
        return Parsed.one(p);
    }

    static String tag(JsonNode ds, String t) {
        JsonNode e = ds.get(t);
        if (e == null || !e.has("Value") || e.get("Value").isEmpty()) return null;
        JsonNode v = e.get("Value").get(0);
        if (v.isObject() && v.has("Alphabetic")) return v.get("Alphabetic").asString();
        return v.asString();
    }

    /** Depth-first search of SR content items (0040A730) for a concept code value (0040A043/00080100). */
    static String[] sr(JsonNode seq, String code) {
        if (seq == null || !seq.has("Value")) return null;
        for (JsonNode item : seq.get("Value")) {
            JsonNode name = item.path("0040A043").path("Value").path(0);
            if (code.equals(tag(name, "00080100"))) {
                JsonNode mv = item.path("0040A300").path("Value").path(0);
                String unit = tag(mv.path("004008EA").path("Value").path(0), "00080100");
                return new String[]{tag(mv, "0040A30A"), unit};
            }
            String[] deeper = sr(item.get("0040A730"), code);
            if (deeper != null) return deeper;
        }
        return null;
    }
}
