package com.wellnessz.e31.adapter.fhir;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;

import com.wellnessz.e31.adapter.ParseContext;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.adapter.RecordAssembler;
import com.wellnessz.e31.adapter.RecordParser;
import com.wellnessz.e31.support.EngineException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code FHIR_PATH}: FHIR R4 {@code Observation} or {@code Bundle} (collection / transaction / batch of Device, DeviceMetric,
 * Observation – PoCD IG, PCHA). Validation = strict HAPI parse plus the engine's profile rules (subject by identifier only,
 * status, code, a value or components, UCUM quantities – FHIR vital-signs profile). A Bundle is one record.
 */
public class FhirObservationParser implements RecordParser {

    static final Set<String> BUNDLE_TYPES = Set.of("collection", "transaction", "batch");
    static final Set<String> ENTRY_TYPES = Set.of("Device", "DeviceMetric", "Observation", "Provenance");
    static final String UCUM = "http://unitsofmeasure.org";

    private final FhirContext fhir;
    private final JsonMapper json;

    public FhirObservationParser(FhirContext fhir, JsonMapper json) {
        this.fhir = fhir;
        this.json = json;
    }

    @Override
    public String parseType() {
        return "FHIR_PATH";
    }

    @Override
    public List<ParsedRecord> parse(ParseContext ctx) {
        String body = new String(ctx.body(), StandardCharsets.UTF_8);
        try {
            IParser parser = fhir.newJsonParser();
            parser.setParserErrorHandler(new StrictErrorHandler());
            parser.parseResource(body);
        } catch (DataFormatException | IllegalArgumentException e) {
            throw EngineException.parse("FHIR resource failed strict validation");
        }
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw EngineException.parse("FHIR resource is not valid JSON");
        }
        List<JsonNode> resources = resources(root);
        List<JsonNode> observations = resources.stream().filter(r -> "Observation".equals(r.path("resourceType").asString(""))).toList();
        if (observations.isEmpty()) {
            throw EngineException.profile("No Observation in the resource");
        }
        for (JsonNode o : observations) {
            checkObservation(o);
        }
        RecordAssembler.SelectorSource src = new RecordAssembler.SelectorSource() {
            @Override
            public String value(String selector) {
                return MiniFhirPath.first(resources, selector);
            }

            @Override
            public String unit(String selector) {
                if (selector.endsWith(".valueQuantity.value")) {
                    String base = selector.substring(0, selector.length() - ".value".length());
                    String code = MiniFhirPath.first(resources, base + ".code");
                    return code != null ? code : MiniFhirPath.first(resources, base + ".unit");
                }
                return null;
            }
        };
        return List.of(RecordAssembler.assemble(0, ctx.pkg(), src, ctx.zone(), ctx.receivedAt()));
    }

    static List<JsonNode> resources(JsonNode root) {
        String type = root.path("resourceType").asString("");
        List<JsonNode> out = new ArrayList<>();
        if ("Bundle".equals(type)) {
            String bt = root.path("type").asString("");
            if (!BUNDLE_TYPES.contains(bt)) {
                throw EngineException.profile("Bundle type " + bt + " is not accepted");
            }
            for (JsonNode e : root.path("entry")) {
                JsonNode r = e.get("resource");
                if (r == null) {
                    continue;
                }
                String rt = r.path("resourceType").asString("");
                if (!ENTRY_TYPES.contains(rt)) {
                    throw EngineException.profile("Bundle entry " + rt + " is not accepted");
                }
                out.add(r);
            }
        } else if ("Observation".equals(type)) {
            out.add(root);
        } else {
            throw EngineException.profile("Resource type " + type + " is not accepted");
        }
        return out;
    }

    static void checkObservation(JsonNode o) {
        if (o.path("status").asString("").isEmpty()) {
            throw EngineException.profile("Observation.status is required");
        }
        if (!o.has("code")) {
            throw EngineException.profile("Observation.code is required");
        }
        JsonNode subject = o.get("subject");
        if (subject != null && subject.has("reference") && !subject.has("identifier")) {
            throw EngineException.profile("Observation.subject must be given by identifier");
        }
        boolean value = o.propertyNames().stream().anyMatch(n -> n.startsWith("value")) || o.has("component") || o.has("dataAbsentReason");
        if (!value) {
            throw EngineException.profile("Observation without value or components");
        }
        List<JsonNode> quantities = new ArrayList<>();
        if (o.has("valueQuantity")) {
            quantities.add(o.get("valueQuantity"));
        }
        for (JsonNode c : o.path("component")) {
            if (c.has("valueQuantity")) {
                quantities.add(c.get("valueQuantity"));
            }
        }
        for (JsonNode q : quantities) {
            if (!UCUM.equals(q.path("system").asString("")) || q.path("code").asString("").isEmpty()) {
                throw EngineException.profile("Quantities must carry a UCUM unit");
            }
        }
    }
}
