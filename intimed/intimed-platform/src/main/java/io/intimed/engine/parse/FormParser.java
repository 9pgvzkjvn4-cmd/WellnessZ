package io.intimed.engine.parse;

import io.intimed.common.Json;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P9 image capture & OCR fallback and P10 manual entry with device attestation.
 * <ul>
 *   <li>OCR_TEXT: text recognised from a photo of the device display/printout (the OCR engine runs on the capturing
 *       device or a pluggable OCR service); driver regexes propose values with confidence → always verify-before-post.</li>
 *   <li>MANUAL: values typed by the clinician; attestation (model, serial, calibration date, attester) is mandatory.</li>
 * </ul>
 */
public class FormParser implements PayloadParser {
    private final String type;

    public FormParser(String type) { this.type = type; }

    @Override public String type() { return type; }

    @Override
    public List<Parsed> parse(JsonNode g, String payload) {
        PayloadParser.guard(payload, 65536);
        JsonNode env;
        try { env = Json.parse(payload); } catch (RuntimeException e) { throw new ParseException("form payload is not JSON"); }
        Parsed p = new Parsed();
        p.id("serial", env.path("serial").asString(null)).id("time", env.path("time").asString(null)).id("subject", env.path("subject").asString(null));
        p.extras.put("verifyRequired", "true");
        if ("OCR_TEXT".equals(type)) {
            String text = env.path("text").asString("");
            if (text.isBlank()) throw new ParseException("no OCR text");
            double conf = env.path("ocrConfidence").asDouble(0);
            p.extras.put("ocrConfidence", String.valueOf(conf));
            for (JsonNode f : g.path("fields")) {
                Matcher m = Pattern.compile(f.get("regex").asString(), Pattern.CASE_INSENSITIVE).matcher(text);
                if (m.find()) p.val(f.get("key").asString(), m.group(1), f.path("unit").asString(null));
            }
        } else {
            JsonNode att = env.path("attestation");
            for (String req : List.of("model", "serial", "calibrationDate", "attestedBy")) {
                if (att.path(req).asString("").isBlank()) throw new ParseException("attestation field '" + req + "' is mandatory");
            }
            p.extras.put("attestation", Json.write(att));
            p.id("serial", att.path("serial").asString(null));
            for (JsonNode f : g.path("fields")) {
                JsonNode v = env.path("values").get(f.get("key").asString());
                if (v != null && !v.isNull()) p.val(f.get("key").asString(), v.asString(), f.path("unit").asString(null));
            }
        }
        if (p.values.isEmpty()) throw new ParseException("no values recognised");
        return Parsed.one(p);
    }
}
