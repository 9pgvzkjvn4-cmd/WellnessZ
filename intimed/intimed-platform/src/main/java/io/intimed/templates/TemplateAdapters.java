package io.intimed.templates;

import io.intimed.common.Hashing;
import io.intimed.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * Template adapters (IM-E05-F03 / IM-E05-BL-003): render one template instance for the host product as FHIR R4
 * (transaction Bundle of Observations + Provenance), HL7 v2.6 ORU^R01 (PCD-01 style), host REST JSON (embedded post-back)
 * or a PDF attachment. Each returns its payload and an acknowledgement that the round-trip check (XT-09) reads back.
 */
@Component
public class TemplateAdapters {
    public static final String MDC = "urn:iso:std:iso:11073:10101";

    public ObjectNode render(String adapter, JsonNode tpl, JsonNode instance) {
        return switch (adapter) {
            case "FHIR" -> fhir(tpl, instance);
            case "HL7V2" -> hl7(tpl, instance);
            case "REST" -> rest(tpl, instance);
            case "PDF" -> pdf(tpl, instance);
            default -> throw new IllegalArgumentException("unknown adapter " + adapter);
        };
    }

    ObjectNode fhir(JsonNode tpl, JsonNode inst) {
        ObjectNode b = Json.obj().put("resourceType", "Bundle").put("type", "transaction");
        ArrayNode entries = b.putArray("entry");
        ArrayNode targets = Json.arr();
        inst.path("fields").properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            if (def == null) return;
            String id = Hashing.stableId("obs", inst.path("id").asString() + e.getKey());
            ObjectNode o = Json.obj().put("resourceType", "Observation").put("id", id).put("status", "final");
            o.putArray("category").addObject().putArray("coding").addObject()
                    .put("system", "http://terminology.hl7.org/CodeSystem/observation-category").put("code", "vital-signs");
            ArrayNode coding = o.putObject("code").putArray("coding");
            if (def.at("/code/loinc").isString()) coding.addObject().put("system", "http://loinc.org").put("code", def.at("/code/loinc").asString()).put("display", def.path("label").asString());
            if (def.at("/code/mdc").isString()) coding.addObject().put("system", MDC).put("code", def.at("/code/mdc").asString());
            o.putObject("subject").putObject("identifier").put("value", inst.path("subject").asString("unknown"));
            o.put("effectiveDateTime", inst.path("effectiveTime").asString());
            o.putObject("device").putObject("identifier").put("value", inst.path("deviceSerial").asString(""));
            JsonNode v = e.getValue();
            if (v.path("value").isNumber()) o.putObject("valueQuantity").put("value", v.get("value").decimalValue()).put("unit", v.path("unit").asString(""))
                    .put("system", "http://unitsofmeasure.org").put("code", v.path("unit").asString(""));
            else o.put("valueString", v.path("value").asString());
            JsonNode rr = v.path("referenceRange");
            if (rr.isObject() && !rr.path("gap").asBoolean(false)) {
                ObjectNode r = o.putArray("referenceRange").addObject();
                String u = v.path("unit").asString("");
                if (rr.has("low")) r.putObject("low").put("value", rr.get("low").decimalValue()).put("unit", u).put("system", "http://unitsofmeasure.org").put("code", u);
                if (rr.has("high")) r.putObject("high").put("value", rr.get("high").decimalValue()).put("unit", u).put("system", "http://unitsofmeasure.org").put("code", u);
                r.putObject("type").putArray("coding").addObject().put("system", "http://terminology.hl7.org/CodeSystem/referencerange-meaning")
                        .put("code", "normal").put("display", "Normal Range");
                r.put("text", rr.path("text").asString() + " (" + rr.path("partition").asString() + "; " + rr.path("source").asString() + ")");
            }
            JsonNode it = v.path("interpretation");
            if (it.isObject()) {
                ObjectNode ic = o.putArray("interpretation").addObject();
                ic.putArray("coding").addObject().put("system", it.path("system").asString()).put("code", it.path("code").asString()).put("display", it.path("display").asString());
                if (v.has("grade")) ic.put("text", v.get("grade").asString());
            }
            entries.addObject().put("fullUrl", "urn:uuid:" + id).set("resource", o);
            entries.get(entries.size() - 1).withObject("request").put("method", "PUT").put("url", "Observation/" + id);
            targets.addObject().put("reference", "Observation/" + id);
        });
        ObjectNode prov = Json.obj().put("resourceType", "Provenance").put("recorded", inst.path("filedAt").asString());
        prov.set("target", targets);
        prov.putArray("agent").addObject().putObject("who").put("display", "IntiMed engine " + inst.at("/provenance/driver").asString(""));
        prov.putArray("entity").addObject().put("role", "source").putObject("what").putObject("identifier")
                .put("system", "urn:intimed:config-hash").put("value", inst.at("/provenance/configHash").asString(""));
        entries.addObject().put("fullUrl", "urn:uuid:prov-" + inst.path("id").asString()).set("resource", prov);
        return Json.obj().put("adapter", "FHIR").put("contentType", "application/fhir+json").put("ack", "201 Created x" + targets.size()).set("payload", b);
    }

    ObjectNode hl7(JsonNode tpl, JsonNode inst) {
        String ts = java.time.OffsetDateTime.parse(inst.path("filedAt").asString()).withOffsetSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + "+0000";
        String ctl = Hashing.sha256(inst.path("id").asString()).substring(0, 16).toUpperCase();
        StringBuilder sb = new StringBuilder();
        sb.append("MSH|^~\\&|INTIMED|INTIMED|HOST|").append(tpl.path("id").asString()).append('|').append(ts).append("||ORU^R01^ORU_R01|")
                .append(ctl).append("|P|2.6|||AL|NE||UNICODE UTF-8|||IHE_PCD_001^IHE PCD^1.3.6.1.4.1.19376.1.6.1.1.1^ISO\r");
        sb.append("PID|||").append(inst.path("subject").asString("")).append("^^^HOST^MR\r");
        sb.append("OBR|1|").append(inst.path("id").asString()).append("||").append(tpl.path("id").asString()).append('^')
                .append(tpl.path("name").asString()).append("^INTIMED|||").append(ts).append("\r");
        int[] n = {0};
        inst.path("fields").properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            if (def == null) return;
            JsonNode v = e.getValue();
            String code = def.at("/code/loinc").isString() ? def.at("/code/loinc").asString() + "^" + def.path("label").asString() + "^LN" : e.getKey() + "^" + def.path("label").asString() + "^L";
            sb.append("OBX|").append(++n[0]).append('|').append(v.path("value").isNumber() ? "NM" : "ST").append('|').append(code).append("||")
                    .append(v.path("value").asString()).append('|').append(v.path("unit").asString("")).append("^^UCUM|").append(obx7(v)).append('|')
                    .append(v.at("/interpretation/code").asString("")).append("|||F|||").append(ts)
                    .append("||||").append(inst.path("deviceSerial").asString("")).append("\r");
        });
        return Json.obj().put("adapter", "HL7V2").put("contentType", "x-application/hl7-v2+er7").put("payload", sb.toString())
                .put("ack", "MSA|AA|" + ctl);
    }

    /** OBX-7 reference range (HL7 v2.6 "low-high", "&gt;low" or "&lt;high"). */
    static String obx7(JsonNode v) {
        JsonNode rr = v.path("referenceRange");
        if (!rr.isObject() || rr.path("gap").asBoolean(false)) return "";
        String lo = rr.has("low") ? rr.get("low").decimalValue().stripTrailingZeros().toPlainString() : null;
        String hi = rr.has("high") ? rr.get("high").decimalValue().stripTrailingZeros().toPlainString() : null;
        if (lo != null && hi != null) return lo + "-" + hi;
        if (lo != null) return ">" + lo;
        return hi == null ? "" : "<" + hi;
    }

    ObjectNode rest(JsonNode tpl, JsonNode inst) {
        ObjectNode body = Json.obj().put("template", tpl.path("id").asString()).put("templateVersion", tpl.path("version").asString())
                .put("instanceId", inst.path("id").asString()).put("subject", inst.path("subject").asString()).put("effectiveTime", inst.path("effectiveTime").asString());
        body.set("fields", inst.path("fields"));
        body.set("provenance", inst.path("provenance"));
        return Json.obj().put("adapter", "REST").put("contentType", "application/json").put("ack", "200 OK").set("payload", body);
    }

    /** Minimal PDF 1.4 (Helvetica, one page) – attachment for hosts that accept documents only. */
    ObjectNode pdf(JsonNode tpl, JsonNode inst) {
        StringBuilder text = new StringBuilder();
        text.append("BT /F1 14 Tf 50 790 Td (").append(esc(tpl.path("name").asString())).append(") Tj ET\n");
        int y = 765;
        text.append("BT /F1 9 Tf 50 ").append(y).append(" Td (Subject: ").append(esc(inst.path("subject").asString())).append("   Effective: ")
                .append(esc(inst.path("effectiveTime").asString())).append("   Device: ").append(esc(inst.path("deviceSerial").asString(""))).append(") Tj ET\n");
        y -= 22;
        var it = inst.path("fields").properties().iterator();
        while (it.hasNext() && y > 60) {
            var e = it.next();
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            String label = def == null ? e.getKey() : def.path("label").asString();
            text.append("BT /F1 10 Tf 50 ").append(y).append(" Td (").append(esc(label)).append(": ").append(esc(e.getValue().path("value").asString()))
                    .append(' ').append(esc(e.getValue().path("unit").asString(""))).append(") Tj ET\n");
            y -= 15;
        }
        text.append("BT /F1 7 Tf 50 40 Td (Generated by IntiMed - provenance ").append(esc(inst.at("/provenance/configHash").asString(""))).append(") Tj ET\n");
        String stream = text.toString();
        String[] objs = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
                "<< /Length " + stream.getBytes(StandardCharsets.ISO_8859_1).length + " >>\nstream\n" + stream + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"};
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offs = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            offs[i] = pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length;
            pdf.append(i + 1).append(" 0 obj\n").append(objs[i]).append("\nendobj\n");
        }
        int xref = pdf.toString().getBytes(StandardCharsets.ISO_8859_1).length;
        pdf.append("xref\n0 ").append(objs.length + 1).append("\n0000000000 65535 f \n");
        for (int o : offs) pdf.append(String.format("%010d 00000 n \n", o));
        pdf.append("trailer\n<< /Size ").append(objs.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        byte[] bytes = pdf.toString().getBytes(StandardCharsets.ISO_8859_1);
        return Json.obj().put("adapter", "PDF").put("contentType", "application/pdf").put("ack", "stored " + bytes.length + " bytes")
                .put("sha256", Hashing.sha256(bytes)).put("payloadBase64", Base64.getEncoder().encodeToString(bytes));
    }

    private static String esc(String s) {
        return s == null ? "" : s.replaceAll("[^\\x20-\\x7E]", "?").replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
    }
}
