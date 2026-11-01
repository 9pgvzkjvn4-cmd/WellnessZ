package io.intimed.engine.parse;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * P8 FHIR R4 inbound (PoCD IG / PCHA uploads): Observation or Bundle of Observations. Fields are selected by
 * {@code system|code} (LOINC or MDC urn:iso:std:iso:11073:10101) on Observation.code or component.code. Resource type,
 * status and valueQuantity are validated (profile-light validation).
 */
public class FhirObservationParser implements PayloadParser {
    @Override public String type() { return "FHIR"; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, MAX_PAYLOAD);
        JsonNode root;
        try { root = Json.parse(payload); } catch (RuntimeException e) { throw new ParseException("not FHIR JSON"); }
        List<JsonNode> obs = new ArrayList<>();
        String rt = root.path("resourceType").asString("");
        if ("Bundle".equals(rt)) root.path("entry").forEach(e -> { if ("Observation".equals(e.path("resource").path("resourceType").asString(""))) obs.add(e.get("resource")); });
        else if ("Observation".equals(rt)) obs.add(root);
        else throw new ParseException("unsupported resourceType " + rt);
        if (obs.isEmpty()) throw new ParseException("no Observation resources");
        Parsed p = new Parsed();
        for (JsonNode o : obs) {
            String st = o.path("status").asString("");
            if (!List.of("final", "amended", "corrected", "preliminary").contains(st)) throw new ParseException("Observation.status invalid");
            p.id("subject", o.path("subject").path("identifier").path("value").asString(null));
            p.id("serial", o.path("device").path("identifier").path("value").asString(null));
            p.id("time", o.path("effectiveDateTime").asString(null));
            for (JsonNode f : g.path("fields")) {
                String[] sc = f.get("code").asString().split("\\|");
                JsonNode q = match(o, sc[0], sc[1]);
                if (q != null) p.val(f.get("key").asString(), q.path("value").asString(null), q.path("code").asString(q.path("unit").asString(null)));
            }
        }
        if (p.values.isEmpty()) throw new ParseException("no mapped codes in FHIR payload");
        return Parsed.one(p);
    }

    private static JsonNode match(JsonNode o, String system, String code) {
        if (hasCode(o.path("code"), system, code) && o.has("valueQuantity")) return o.get("valueQuantity");
        for (JsonNode c : o.path("component")) if (hasCode(c.path("code"), system, code) && c.has("valueQuantity")) return c.get("valueQuantity");
        return null;
    }

    private static boolean hasCode(JsonNode cc, String system, String code) {
        for (JsonNode c : cc.path("coding")) if (system.equals(c.path("system").asString("")) && code.equals(c.path("code").asString(""))) return true;
        return false;
    }
}
