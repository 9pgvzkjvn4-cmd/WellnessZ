package io.intimed.intelligence;

import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.engine.driver.DriverPackage;
import io.intimed.engine.driver.DriverRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.Map;

/**
 * Precise integration profile per device model (IM-E12-F02): device-type taxonomy (with IEEE 11073-104xx
 * specialisation), data class, integration mechanism per connectivity option (protocol, transport, vendor gateway,
 * direction, security), association method, capture mode and mapping precision (share of driver semantics bound to a
 * standard code, unit and clinical reference interval).
 */
@Service
public class IntegrationProfiles {
    private static final Map<String, String[]> OPTION = Map.ofEntries(
            Map.entry("P1", new String[]{"Serial / USB (RS-232, USB-CDC)", "edge gateway serial listener", "frame grammar with checksum"}),
            Map.entry("P2", new String[]{"File export (CSV, XML, GDT, PDF)", "watched folder / SFTP drop", "file grammar"}),
            Map.entry("P3", new String[]{"Bluetooth LE (GATT, IEEE 11073-20601 PHD)", "mobile / edge BLE bridge", "GATT characteristic decoding (SFLOAT)"}),
            Map.entry("P4", new String[]{"HL7 v2 over MLLP-TLS (IHE PCD-01, ORU)", "MLLP listener :2575 with mTLS", "ER7 segment grammar"}),
            Map.entry("P5", new String[]{"ASTM E1394 / LIS2-A2", "LIS listener / serial", "record grammar"}),
            Map.entry("P6", new String[]{"POCT1-A XML", "POCT data manager", "XML grammar"}),
            Map.entry("P7", new String[]{"Vendor cloud API / webhook (REST/JSON)", "HTTPS ingress :8443 with mTLS / OAuth", "JSON path grammar"}),
            Map.entry("P8", new String[]{"DICOM (SR / RDSR, DICOM JSON)", "DICOM gateway", "DICOM JSON grammar"}),
            Map.entry("P9", new String[]{"IEEE 11073 SDC (BICEPS)", "SDC consumer", "SDC JSON grammar"}),
            Map.entry("P10", new String[]{"FHIR R4 Observation (PHD / PCHA)", "FHIR ingress", "FHIR Observation grammar"}),
            Map.entry("P11", new String[]{"Screen / paper capture (OCR, manual)", "capture component", "form grammar with verify-before-post"}));

    private final JsonNode devices = Json.resource("seed/devices.json");
    private final JsonNode types = Json.resource("seed/device-types.json");
    private final Map<String, JsonNode> typeByCode = new HashMap<>();
    private final Map<String, JsonNode> deviceById = new HashMap<>();
    private final DriverRegistry drivers;
    private final ReferenceRanges ranges;

    public IntegrationProfiles(DriverRegistry drivers, ReferenceRanges ranges) {
        this.drivers = drivers;
        this.ranges = ranges;
        types.forEach(t -> typeByCode.put(t.get("code").asString(), t));
        devices.forEach(d -> deviceById.put(d.get("id").asString(), d));
    }

    public JsonNode types() { return types; }

    public String deviceType(String modelId) {
        JsonNode d = deviceById.get(modelId);
        return d == null ? null : d.path("deviceType").asString(null);
    }

    public ObjectNode taxonomy() {
        ObjectNode out = Json.obj();
        ArrayNode a = out.putArray("types");
        Map<String, Integer> count = new HashMap<>();
        devices.forEach(d -> count.merge(d.path("deviceType").asString("UNCLASSIFIED"), 1, Integer::sum));
        for (JsonNode t : types) {
            ObjectNode o = ((ObjectNode) t.deepCopy()).put("models", count.getOrDefault(t.get("code").asString(), 0));
            a.add(o);
        }
        int rule = 0;
        for (JsonNode d : devices) if ("RULE_MATCH".equals(d.path("deviceTypeBasis").asString())) rule++;
        out.put("models", devices.size()).put("ruleMatched", rule).put("familyDefault", devices.size() - rule);
        return out;
    }

    public ObjectNode forModel(String tenant, String modelId) {
        JsonNode d = deviceById.get(modelId);
        if (d == null) throw ApiException.notFound("model " + modelId);
        ObjectNode p = Json.obj().put("modelId", modelId).put("model", d.path("model").asString()).put("manufacturer", d.path("manufacturer").asString())
                .put("family", d.path("family").asString());
        String tc = d.path("deviceType").asString(null);
        JsonNode t = tc == null ? null : typeByCode.get(tc);
        ObjectNode type = p.putObject("deviceType").put("code", tc).put("basis", d.path("deviceTypeBasis").asString()).put("confidence", d.path("deviceTypeConfidence").asDouble());
        if (t != null) {
            type.put("name", t.path("name").asString()).put("dataClass", t.path("dataClass").asString());
            if (t.hasNonNull("ieee11073Specialization")) type.put("ieee11073Specialization", "IEEE 11073-" + t.get("ieee11073Specialization").asString());
        }
        p.put("vendorConnectivity", d.path("connectivity").asString()).put("vendorProtocol", d.path("protocol").asString());
        ArrayNode mech = p.putArray("mechanisms");
        for (JsonNode o : d.path("options")) {
            String[] x = OPTION.get(o.asString());
            if (x == null) continue;
            mech.addObject().put("option", o.asString()).put("protocol", x[0]).put("transport", x[1]).put("grammar", x[2]);
        }
        ArrayNode drv = p.putArray("drivers");
        for (DriverPackage pk : drivers.forModel(tenant, modelId)) {
            ObjectNode o = drv.addObject().put("ref", pk.ref()).put("name", pk.name()).put("captureMode", pk.manifest().path("captureMode").asString())
                    .put("bidirectional", pk.manifest().path("bidirectional").asBoolean(false)).put("signature", pk.signatureStatus())
                    .put("certification", pk.manifest().at("/certification/status").asString(""));
            ArrayNode tr = o.putArray("transport");
            pk.json().path("transport").properties().forEach(e -> tr.addObject().put("option", e.getKey())
                    .put("security", e.getValue().path("security").toString()).set("parameters", e.getValue().path("parameters")));
            int std = 0, unit = 0, rr = 0, numeric = 0;
            for (JsonNode s : pk.semantics()) {
                if ("text".equals(s.path("type").asString())) continue;
                numeric++;
                if (s.has("loinc") || s.has("mdc")) std++;
                if (s.has("ucum")) unit++;
                if (ranges.find(s, s.get("key").asString()) != null) rr++;
            }
            o.putObject("mappingPrecision").put("numericKeys", numeric).put("standardCoded", std).put("ucumUnits", unit).put("referenceIntervals", rr)
                    .put("score", numeric == 0 ? 0 : Math.round(1000.0 * (std + unit + rr) / (3.0 * numeric)) / 1000.0);
            o.set("templates", Json.tree(pk.bindings().propertyNames()));
        }
        p.put("driverAvailable", !drv.isEmpty());
        return p;
    }
}
