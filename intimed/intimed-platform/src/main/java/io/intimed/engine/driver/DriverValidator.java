package io.intimed.engine.driver;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Structural validation of a driver package (used by the registry, the SDK {@code validate} command and driver CI). */
public final class DriverValidator {
    public static final Set<String> OPTIONS = Set.of("P1", "P2", "P3", "P4", "P5", "P6", "P7", "P8", "P9", "P10", "P11");
    public static final Set<String> GRAMMARS = Set.of("HL7V2", "ASTM", "SERIAL", "CSV", "XML", "POCT1A", "GDT", "BLE_GATT", "JSON", "SDC",
            "DICOM_JSON", "FHIR", "OCR_TEXT", "MANUAL");

    private DriverValidator() { }

    public static List<String> validate(JsonNode p) {
        List<String> e = new ArrayList<>();
        for (String s : List.of("manifest", "transport", "grammar", "semantics", "bindings", "recommendations", "tests", "docs")) {
            if (!p.has(s)) e.add("missing section " + s);
        }
        if (!e.isEmpty()) return e;
        JsonNode m = p.get("manifest");
        if (!m.path("packageId").asString("").matches("[a-z0-9]+(\\.[a-z0-9-]+)+")) e.add("manifest.packageId must be dotted lower-case");
        if (!m.path("version").asString("").matches("\\d+\\.\\d+\\.\\d+")) e.add("manifest.version must be semver");
        if (m.path("models").isEmpty()) e.add("manifest.models empty");
        for (JsonNode o : m.path("options")) {
            if (!OPTIONS.contains(o.asString())) e.add("unknown option " + o.asString());
            if (!p.get("transport").has(o.asString())) e.add("transport missing for " + o.asString());
        }
        if (!GRAMMARS.contains(p.at("/grammar/type").asString(""))) e.add("unknown grammar type");
        for (JsonNode s : p.get("semantics")) {
            if (!s.has("key") || !s.has("display")) e.add("semantic without key/display");
            if (!s.has("loinc") && !s.has("mdc") && !s.has("local") && !s.path("type").asString("").equals("text")) e.add("semantic " + s.path("key").asString() + " has no LOINC, MDC or local code");
        }
        if (p.at("/tests/golden").isMissingNode() || p.at("/tests/golden").isEmpty()) e.add("tests.golden empty");
        return e;
    }
}
