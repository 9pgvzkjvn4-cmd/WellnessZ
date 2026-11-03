package io.intimed.intelligence;

import io.intimed.clinical.Demographics;
import io.intimed.clinical.PlatformCategories;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.Json;
import io.intimed.engine.terminology.Terminology;
import io.intimed.templates.TemplateAdapters;
import io.intimed.templates.TemplateRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comprehensive validation framework (IM-E14-F02): validates a mapped template instance against the rule pack of a
 * healthcare-platform category in layers – STRUCTURE, TERMINOLOGY, UNITS, PLAUSIBILITY, CLINICAL_RANGE (age/sex/pregnancy
 * partitioned reference intervals with HL7 interpretation), CROSS_FIELD (physiological consistency), CATEGORY_GRADES,
 * REGULATORY (occupational thresholds), LAB_RANGE_REQUIRED, DOSE_REFERENCE (DRL) and CONSENT – and renders the output
 * exactly as the host would receive it (FHIR referenceRange/interpretation, HL7 OBX-7/OBX-8).
 */
@Service
public class ValidationEngine {
    private final ReferenceRanges ranges;
    private final PlatformCategories categories;
    private final Terminology terms;
    private final TemplateAdapters adapters;

    public ValidationEngine(ReferenceRanges ranges, PlatformCategories categories, Terminology terms, TemplateAdapters adapters) {
        this.ranges = ranges;
        this.categories = categories;
        this.terms = terms;
        this.adapters = adapters;
    }

    public record Finding(String layer, String severity, String field, String message) { }

    /**
     * @param fields  mapped field values (as produced by the router: value, unit, referenceRange, interpretation …)
     * @param context consent, regulation and other category parameters supplied by the caller
     */
    public ObjectNode validate(JsonNode tpl, ObjectNode fields, String categoryId, Demographics demo, JsonNode context) {
        JsonNode cat = categoryId == null ? null : categories.require(categoryId);
        List<String> rules = new ArrayList<>();
        if (cat == null) rules.addAll(List.of("STRUCTURE", "TERMINOLOGY", "UNITS", "PLAUSIBILITY", "CLINICAL_RANGE", "CROSS_FIELD"));
        else cat.path("rules").forEach(r -> rules.add(r.asString()));
        if (!rules.contains("PLAUSIBILITY")) rules.add(rules.contains("UNITS") ? rules.indexOf("UNITS") + 1 : rules.size(), "PLAUSIBILITY");
        JsonNode params = cat == null ? Json.obj() : cat.path("parameters");
        Demographics d = demo.withContext(context).withContext(params);
        // (re-)annotate so the category's context (e.g. regulation, altitude) is reflected
        fields.properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            if (def != null && e.getValue().path("value").isNumber() && cat != null && !"informational".equals(cat.path("interpretation").asString()))
                ranges.annotate((ObjectNode) e.getValue(), def, e.getKey(), d);
            if (cat != null && "informational".equals(cat.path("interpretation").asString()) && e.getValue().isObject()) {
                ((ObjectNode) e.getValue()).remove(List.of("interpretation", "grade"));
            }
        });
        List<Finding> f = new ArrayList<>();
        for (String r : rules) {
            switch (r) {
                case "STRUCTURE" -> structure(tpl, fields, f);
                case "TERMINOLOGY" -> terminology(tpl, fields, cat, f);
                case "UNITS" -> units(tpl, fields, f);
                case "PLAUSIBILITY" -> plausibility(tpl, fields, f);
                case "CLINICAL_RANGE" -> clinical(tpl, fields, f);
                case "CROSS_FIELD" -> crossField(tpl, fields, f);
                case "CATEGORY_GRADES" -> grades(tpl, fields, f);
                case "REGULATORY" -> regulatory(tpl, fields, d, f);
                case "LAB_RANGE_REQUIRED" -> labRange(tpl, fields, f);
                case "DOSE_REFERENCE" -> dose(tpl, fields, params, context, f);
                case "CONSENT" -> consent(context, f);
                default -> { }
            }
        }
        ObjectNode out = Json.obj().put("templateId", tpl.get("id").asString()).put("templateName", tpl.path("name").asString())
                .put("category", categoryId).put("categoryName", cat == null ? "Generic" : cat.path("name").asString())
                .put("interpretationPolicy", cat == null ? "flag" : cat.path("interpretation").asString())
                .put("demographics", d.describe()).put("ageBand", ranges.ageBand(d.ageYears()));
        if (cat != null) out.put("criticalHandling", cat.path("criticalHandling").asString(""));
        ArrayNode layers = out.putArray("layers");
        Map<String, List<Finding>> byLayer = new LinkedHashMap<>();
        rules.forEach(r -> byLayer.put(r, new ArrayList<>()));
        f.forEach(x -> byLayer.computeIfAbsent(x.layer(), k -> new ArrayList<>()).add(x));
        int errors = 0, warnings = 0;
        for (var e : byLayer.entrySet()) {
            String status = e.getValue().stream().anyMatch(x -> x.severity().equals("ERROR")) ? "FAIL"
                    : e.getValue().stream().anyMatch(x -> x.severity().equals("WARN")) ? "WARN" : "PASS";
            ObjectNode l = layers.addObject().put("layer", e.getKey()).put("status", status);
            ArrayNode fa = l.putArray("findings");
            for (Finding x : e.getValue()) {
                fa.addObject().put("severity", x.severity()).put("field", x.field()).put("message", x.message());
                if (x.severity().equals("ERROR")) errors++;
                if (x.severity().equals("WARN")) warnings++;
            }
        }
        out.put("errors", errors).put("warnings", warnings).put("verdict", errors > 0 ? "FAIL" : warnings > 0 ? "PASS_WITH_WARNINGS" : "PASS");
        ArrayNode rows = out.putArray("fields");
        for (JsonNode def : TemplateRegistry.fields(tpl)) {
            String id = def.get("id").asString();
            JsonNode v = fields.get(id);
            ObjectNode row = rows.addObject().put("id", id).put("label", def.path("label").asString()).put("mandatory", def.path("mandatory").asBoolean(false))
                    .put("code", codeText(def)).put("templateUnit", def.path("unit").asString(""));
            if (v == null) { row.put("present", false); continue; }
            row.put("present", true).set("value", v.get("value"));
            row.put("unit", v.path("unit").asString(""));
            if (v.has("convertedFrom")) row.put("convertedFrom", v.get("convertedFrom").asString());
            if (v.has("referenceRange")) row.set("referenceRange", v.get("referenceRange"));
            if (v.has("interpretation")) row.set("interpretation", v.get("interpretation"));
            if (v.has("grade")) row.put("grade", v.get("grade").asString());
        }
        ObjectNode inst = Json.obj().put("id", "validation-" + tpl.get("id").asString()).put("templateId", tpl.get("id").asString()).put("subject", "SYNTHETIC")
                .put("effectiveTime", Instant.now().toString()).put("filedAt", Instant.now().toString()).put("deviceSerial", "SIM");
        inst.set("fields", fields);
        inst.putObject("provenance").put("driver", "validation-studio").put("configHash", "dry-run");
        ObjectNode outputs = out.putObject("outputs");
        outputs.set("FHIR", adapters.render("FHIR", tpl, inst).get("payload"));
        outputs.put("HL7V2", adapters.render("HL7V2", tpl, inst).path("payload").asString());
        return out;
    }

    static String codeText(JsonNode def) {
        JsonNode c = def.path("code");
        if (c.has("loinc")) return "LOINC " + c.get("loinc").asString();
        if (c.has("mdc")) return "MDC " + c.get("mdc").asString();
        if (c.has("local")) return "local " + c.get("local").asString();
        return "—";
    }

    private void structure(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        for (JsonNode def : TemplateRegistry.fields(tpl))
            if (def.path("mandatory").asBoolean(false) && !fields.has(def.get("id").asString()))
                f.add(new Finding("STRUCTURE", "ERROR", def.get("id").asString(), "mandatory field missing"));
        fields.properties().forEach(e -> {
            if (TemplateRegistry.field(tpl, e.getKey()) == null) f.add(new Finding("STRUCTURE", "ERROR", e.getKey(), "field is not part of the template"));
        });
    }

    private void terminology(JsonNode tpl, ObjectNode fields, JsonNode cat, List<Finding> f) {
        boolean std = cat != null && cat.at("/terminology/requireStandardCode").asBoolean(false);
        fields.properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            if (def == null) return;
            JsonNode c = def.path("code");
            if (c.isMissingNode() || c.isEmpty()) f.add(new Finding("TERMINOLOGY", "ERROR", e.getKey(), "no code bound"));
            else if (std && !c.has("loinc") && !c.has("mdc") && e.getValue().path("value").isNumber())
                f.add(new Finding("TERMINOLOGY", "WARN", e.getKey(), "local code only – category expects LOINC or IEEE 11073 MDC"));
        });
    }

    private void units(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        fields.properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            JsonNode v = e.getValue();
            if (def == null || !v.path("value").isNumber()) return;
            String want = def.path("unit").asString(null), got = v.path("unit").asString(null);
            if (want != null && !want.equals(got)) f.add(new Finding("UNITS", "ERROR", e.getKey(), "unit " + got + " ≠ template unit " + want));
            else if (want != null && !terms.known(want) && !want.startsWith("{")) f.add(new Finding("UNITS", "WARN", e.getKey(), "unit " + want + " not in the UCUM synonym table"));
        });
    }

    private void plausibility(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        fields.properties().forEach(e -> {
            JsonNode def = TemplateRegistry.field(tpl, e.getKey());
            JsonNode v = e.getValue();
            if (def == null || !v.path("value").isNumber() || !def.has("range")) return;
            double x = v.get("value").asDouble();
            if (def.at("/range/min").isNumber() && x < def.at("/range/min").asDouble() || def.at("/range/max").isNumber() && x > def.at("/range/max").asDouble())
                f.add(new Finding("PLAUSIBILITY", "ERROR", e.getKey(), "value " + v.get("value").asString() + " outside physically plausible range " + def.get("range")));
        });
    }

    private void clinical(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        fields.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (!v.has("referenceRange")) return;
            if (v.at("/referenceRange/gap").asBoolean(false)) {
                f.add(new Finding("CLINICAL_RANGE", "WARN", e.getKey(), "no reference interval for this partition – " + v.at("/referenceRange/notes/0").asString("")));
                return;
            }
            String code = v.at("/interpretation/code").asString("N");
            if (!"N".equals(code))
                f.add(new Finding("CLINICAL_RANGE", "INFO", e.getKey(), v.at("/interpretation/display").asString() + " (" + code + ") – " + v.get("value").asString() + " vs "
                        + v.at("/referenceRange/text").asString() + " [" + v.at("/referenceRange/partition").asString() + "]"));
        });
    }

    private static Double num(ObjectNode fields, JsonNode tpl, String... entryOrLoinc) {
        for (String k : entryOrLoinc) {
            for (var it = fields.properties().iterator(); it.hasNext(); ) {
                var e = it.next();
                JsonNode def = TemplateRegistry.field(tpl, e.getKey());
                if (def == null || !e.getValue().path("value").isNumber()) continue;
                if (k.equals(def.at("/code/loinc").asString(null)) || k.equals(e.getValue().at("/referenceRange/entry").asString(null)) || k.equals(e.getKey()))
                    return e.getValue().get("value").asDouble();
            }
        }
        return null;
    }

    private void crossField(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        Double sys = num(fields, tpl, "8480-6"), dia = num(fields, tpl, "8462-4"), map = num(fields, tpl, "8478-0");
        if (sys != null && dia != null && sys <= dia) f.add(new Finding("CROSS_FIELD", "ERROR", "systolic/diastolic", "systolic " + sys + " ≤ diastolic " + dia));
        if (sys != null && dia != null && sys - dia < 10 && sys > dia) f.add(new Finding("CROSS_FIELD", "WARN", "pulse pressure", "pulse pressure " + (sys - dia) + " mm[Hg] is implausibly narrow"));
        if (map != null && sys != null && dia != null && (map < dia || map > sys)) f.add(new Finding("CROSS_FIELD", "ERROR", "MAP", "mean arterial pressure " + map + " not between diastolic and systolic"));
        Double fev1 = num(fields, tpl, "20150-9"), fvc = num(fields, tpl, "19870-5", "19868-9"), ratio = num(fields, tpl, "19926-5");
        if (fev1 != null && fvc != null && fev1 > fvc + 0.05) f.add(new Finding("CROSS_FIELD", "ERROR", "FEV1/FVC", "FEV1 " + fev1 + " L exceeds FVC " + fvc + " L"));
        if (fev1 != null && fvc != null && ratio != null && fvc > 0 && Math.abs(fev1 / fvc * 100 - ratio) > 2.0)
            f.add(new Finding("CROSS_FIELD", "WARN", "ratio", String.format("reported ratio %.1f %% differs from FEV1/FVC %.1f %%", ratio, fev1 / fvc * 100)));
        Double qt = num(fields, tpl, "8634-8"), qtc = num(fields, tpl, "8636-3"), hr = num(fields, tpl, "8867-4");
        if (qt != null && qtc != null && hr != null && hr > 0) {
            double bazett = qt / Math.sqrt(60.0 / hr);
            if (Math.abs(bazett - qtc) > 25) f.add(new Finding("CROSS_FIELD", "WARN", "qtc", String.format("QTc %.0f ms differs from Bazett estimate %.0f ms by > 25 ms (other correction formula?)", qtc, bazett)));
        }
        Double ufv = num(fields, tpl, "uf_volume"), ufg = num(fields, tpl, "uf_goal");
        if (ufv != null && ufg != null && ufg > 0 && ufv > ufg * 1.1) f.add(new Finding("CROSS_FIELD", "WARN", "uf_volume", "ultrafiltration volume exceeds goal by > 10 %"));
        Double vtbi = num(fields, tpl, "vtbi"), infused = num(fields, tpl, "infused");
        if (vtbi != null && infused != null && infused > vtbi) f.add(new Finding("CROSS_FIELD", "ERROR", "infused", "volume infused exceeds VTBI"));
        Double air = num(fields, tpl, "air_temp"), skin = num(fields, tpl, "skin_temp");
        if (air != null && skin != null && Math.abs(air - skin) > 3) f.add(new Finding("CROSS_FIELD", "WARN", "skin_temp", "air–skin temperature gradient > 3 °C – probe position?"));
        Double minSpo2 = num(fields, tpl, "min_spo2");
        if (minSpo2 != null && minSpo2 > 100) f.add(new Finding("CROSS_FIELD", "ERROR", "min_spo2", "minimum SpO2 above 100 %"));
    }

    private void grades(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        fields.properties().forEach(e -> {
            if (e.getValue().has("grade")) f.add(new Finding("CATEGORY_GRADES", "INFO", e.getKey(), "grade: " + e.getValue().get("grade").asString()));
        });
    }

    private void regulatory(JsonNode tpl, ObjectNode fields, Demographics d, List<Finding> f) {
        fields.properties().forEach(e -> {
            String entry = e.getValue().at("/referenceRange/entry").asString("");
            String code = e.getValue().at("/interpretation/code").asString("N");
            if ("brac".equals(entry)) {
                if (d.regulation() == null) f.add(new Finding("REGULATORY", "WARN", e.getKey(), "no regulation configured – DOT thresholds shown for information"));
                else if ("HH".equals(code)) f.add(new Finding("REGULATORY", "INFO", e.getKey(), "≥ 0.04 g/210L: " + d.regulation() + " violation – removal from safety-sensitive duty, SAP referral (49 CFR 382.201)"));
                else if ("H".equals(code)) f.add(new Finding("REGULATORY", "INFO", e.getKey(), "0.02–0.039 g/210L: removal from safety-sensitive duty for 24 h (49 CFR 382.505)"));
            }
            if ("fit-factor".equals(entry) && "L".equals(code))
                f.add(new Finding("REGULATORY", "INFO", e.getKey(), "fit factor below pass level for " + (d.respiratorType() == null ? "respirator" : d.respiratorType()) + " – fit test FAIL (29 CFR 1910.134 App A)"));
            if ("hearing-threshold".equals(entry) && !"N".equals(code))
                f.add(new Finding("REGULATORY", "INFO", e.getKey(), "threshold above normal hearing – review for standard threshold shift against baseline (29 CFR 1910.95)"));
        });
    }

    private void labRange(JsonNode tpl, ObjectNode fields, List<Finding> f) {
        fields.properties().forEach(e -> {
            JsonNode v = e.getValue();
            if (!v.path("value").isNumber()) return;
            if (!v.has("referenceRange")) f.add(new Finding("LAB_RANGE_REQUIRED", "ERROR", e.getKey(), "numeric laboratory result without reference interval (ISO 15189 report content)"));
            else if (v.at("/referenceRange/gap").asBoolean(false)) f.add(new Finding("LAB_RANGE_REQUIRED", "ERROR", e.getKey(), "reference interval missing for the subject's partition"));
        });
    }

    private void dose(JsonNode tpl, ObjectNode fields, JsonNode params, JsonNode ctx, List<Finding> f) {
        Double dlp = num(fields, tpl, "dlp");
        if (dlp == null) return;
        double drl = ctx != null && ctx.has("drlCtDlp") ? ctx.get("drlCtDlp").asDouble() : params.path("drlCtDlp").asDouble(600);
        if (dlp > drl) f.add(new Finding("DOSE_REFERENCE", "WARN", "dlp", String.format("DLP %.0f mGy.cm exceeds the local diagnostic reference level %.0f mGy.cm – dose review", dlp, drl)));
        else f.add(new Finding("DOSE_REFERENCE", "INFO", "dlp", String.format("DLP %.0f mGy.cm within DRL %.0f mGy.cm", dlp, drl)));
    }

    private void consent(JsonNode ctx, List<Finding> f) {
        if (ctx == null || !ctx.path("consent").asBoolean(false)) f.add(new Finding("CONSENT", "ERROR", "-", "no active consent for remote / wellness data sharing"));
    }

    /** Numeric helper for tests. */
    static BigDecimal dec(JsonNode n) { return n == null || !n.isNumber() ? null : n.decimalValue(); }
}
