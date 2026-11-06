package io.intimed.clinical;

import io.intimed.common.Json;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Derived values (IM-E14-BL-010): a versioned calculation module applied after mapping. Every result carries the formula
 * id, formula version, the inputs actually used (value + unit) and the label "derived – verify". Derived calculations are
 * clinical functions with regulatory impact, so they are OFF by default and enabled per configuration with
 * {@code params.derivedValues=true}; they never replace a measured value.
 * <ul>
 *   <li>{@code egfr} – CKD-EPI 2021 creatinine equation, race-free (Inker LA et al., NEJM 2021;385:1737): 142 ×
 *       min(Scr/κ,1)^α × max(Scr/κ,1)^−1.200 × 0.9938^age × 1.012 [female]; κ 0.7/0.9, α −0.241/−0.302 (F/M); Scr in
 *       mg/dL (µmol/L ÷ 88.4); adults ≥ 18 y with known sex only.</li>
 *   <li>{@code bmi} – weight (kg) / height (m)² when both are present.</li>
 *   <li>{@code anion-gap} – Na + K − (Cl + HCO3) when K is present, otherwise Na − (Cl + HCO3).</li>
 *   <li>{@code corrected-calcium} – Payne: Ca (mmol/L) + 0.02 × (40 − albumin g/L) when albumin is present.</li>
 * </ul>
 */
@Component
public class DerivedValues {
    public static final String LABEL = "derived – verify";
    public static final String MODULE_VERSION = "2026.11-1";

    /** id, name, unit, formula id, formula version, LOINC codes of a target template field, field ids, KB entry (or null). */
    public record Formula(String id, String name, String unit, String formulaId, String version, List<String> loinc, List<String> fieldIds, String entry) { }

    public static final List<Formula> FORMULAS = List.of(
            new Formula("egfr", "eGFR (CKD-EPI 2021, race-free)", "mL/min/{1.73_m2}", "CKD-EPI-2021-CR", "1.0.0", List.of("98979-8", "62238-1"), List.of("egfr"), "egfr"),
            new Formula("bmi", "Body mass index", "kg/m2", "BMI-QUETELET", "1.0.0", List.of("39156-5"), List.of("bmi"), null),
            new Formula("anion-gap", "Anion gap", "mmol/L", "AG-NA-K-CL-HCO3", "1.0.0", List.of("33037-3", "1863-0", "10466-1"), List.of("anion_gap", "ag"), null),
            new Formula("corrected-calcium", "Calcium corrected for albumin", "mmol/L", "CA-CORR-PAYNE", "1.0.0", List.of("29265-6"), List.of("ca_corrected", "corrected_calcium"), null));

    /** Input concepts: LOINC codes and field/driver keys that identify them. */
    static final Map<String, Set<String>> INPUT_LOINC = Map.of(
            "creatinine", Set.of("14682-9", "2160-0", "38483-4"), "weight", Set.of("29463-7", "3141-9"), "height", Set.of("8302-2", "8306-3"),
            "sodium", Set.of("2951-2", "2947-0"), "potassium", Set.of("2823-3", "6298-4"), "chloride", Set.of("2075-0", "2069-3"),
            "bicarbonate", Set.of("1963-8", "1959-6", "2028-9", "14627-4"), "calcium", Set.of("17861-6", "2000-8"), "albumin", Set.of("1751-7", "61151-7"));
    static final Map<String, Set<String>> INPUT_KEYS = Map.of(
            "creatinine", Set.of("creatinine", "crea", "scr"), "weight", Set.of("weight", "weight_pre", "wt"), "height", Set.of("height", "ht"),
            "sodium", Set.of("na", "sodium"), "potassium", Set.of("k", "potassium"), "chloride", Set.of("cl", "chloride"),
            "bicarbonate", Set.of("hco3", "bicarbonate", "tco2"), "calcium", Set.of("ca", "calcium"), "albumin", Set.of("alb", "albumin"));

    public record Input(BigDecimal value, String unit) { }

    /** Collects inputs (canonical unit) from normalised measurements (key, code.loinc, value, unit). */
    public static Map<String, Input> inputs(JsonNode measurements) {
        Map<String, Input> in = new LinkedHashMap<>();
        if (measurements == null) return in;
        for (JsonNode m : measurements) {
            if (!m.path("value").isNumber()) continue;
            String loinc = m.at("/code/loinc").asString(null), key = m.path("key").asString("").toLowerCase();
            for (String concept : INPUT_LOINC.keySet()) {
                if (in.containsKey(concept)) continue;
                if ((loinc != null && INPUT_LOINC.get(concept).contains(loinc)) || INPUT_KEYS.get(concept).contains(key)) {
                    Input c = canonical(concept, m.get("value").decimalValue(), m.path("unit").asString(null));
                    if (c != null) in.put(concept, c);
                }
            }
        }
        return in;
    }

    /** Converts an input into the formula's canonical unit; null when the unit is not understood (never guessed). */
    static Input canonical(String concept, BigDecimal v, String unit) {
        String u = unit == null ? "" : unit.trim();
        double x = v.doubleValue();
        Double out = switch (concept) {
            case "creatinine" -> switch (u) { case "umol/L", "µmol/L" -> x; case "mg/dL" -> x * 88.4; default -> null; };
            case "weight" -> switch (u) { case "kg" -> x; case "g" -> x / 1000; case "[lb_av]", "lb" -> x * 0.45359237; default -> null; };
            case "height" -> switch (u) { case "cm" -> x; case "m" -> x * 100; case "[in_i]", "in" -> x * 2.54; default -> null; };
            case "sodium", "potassium", "chloride", "bicarbonate" -> switch (u) { case "mmol/L", "meq/L", "[meq]/L", "mEq/L" -> x; default -> null; };
            case "calcium" -> switch (u) { case "mmol/L" -> x; case "mg/dL" -> x / 4.008; default -> null; };
            case "albumin" -> switch (u) { case "g/L" -> x; case "g/dL" -> x * 10; default -> null; };
            default -> null;
        };
        String cu = switch (concept) {
            case "creatinine" -> "umol/L"; case "weight" -> "kg"; case "height" -> "cm"; case "albumin" -> "g/L"; default -> "mmol/L";
        };
        return out == null ? null : new Input(BigDecimal.valueOf(out), cu);
    }

    // ------------------------------------------------------------------------------------------ formulas (pure)
    /** CKD-EPI 2021 race-free creatinine equation; null outside the validated population (adult, known sex). */
    public static Double egfrCkdEpi2021(double creatinineUmolL, Double ageYears, String sex) {
        if (ageYears == null || ageYears < 18 || creatinineUmolL <= 0 || !("female".equals(sex) || "male".equals(sex))) return null;
        boolean f = "female".equals(sex);
        double scr = creatinineUmolL / 88.4, k = f ? 0.7 : 0.9, a = f ? -0.241 : -0.302, r = scr / k;
        return 142 * Math.pow(Math.min(r, 1), a) * Math.pow(Math.max(r, 1), -1.200) * Math.pow(0.9938, ageYears) * (f ? 1.012 : 1.0);
    }

    public static Double bmi(double kg, double cm) { return kg <= 0 || cm <= 0 ? null : kg / Math.pow(cm / 100, 2); }

    public static double anionGap(double na, Double k, double cl, double hco3) { return na + (k == null ? 0 : k) - (cl + hco3); }

    public static double correctedCalcium(double caMmolL, double albuminGL) { return caMmolL + 0.02 * (40 - albuminGL); }

    // ------------------------------------------------------------------------------------------ application
    /** All derived values computable from the measurements and demographics; each is a self-describing JSON object. */
    public ArrayNode derive(JsonNode measurements, Demographics d) {
        Map<String, Input> in = inputs(measurements);
        ArrayNode out = Json.arr();
        Input cr = in.get("creatinine");
        if (cr != null) {
            Double e = egfrCkdEpi2021(cr.value().doubleValue(), d == null ? null : d.ageYears(), d == null ? null : d.sex());
            if (e != null) {
                ObjectNode r = result(FORMULAS.get(0), e, 0);
                ObjectNode i = r.putObject("inputs");
                put(i, "creatinine", cr);
                i.put("ageYears", Math.round(d.ageYears() * 10) / 10.0).put("sex", d.sex());
                out.add(r);
            }
        }
        Input w = in.get("weight"), h = in.get("height");
        if (w != null && h != null) {
            Double b = bmi(w.value().doubleValue(), h.value().doubleValue());
            if (b != null) { ObjectNode r = result(FORMULAS.get(1), b, 1); put(r.putObject("inputs"), "weight", w); put((ObjectNode) r.get("inputs"), "height", h); out.add(r); }
        }
        Input na = in.get("sodium"), k = in.get("potassium"), cl = in.get("chloride"), hco3 = in.get("bicarbonate");
        if (na != null && cl != null && hco3 != null) {
            double ag = anionGap(na.value().doubleValue(), k == null ? null : k.value().doubleValue(), cl.value().doubleValue(), hco3.value().doubleValue());
            ObjectNode r = result(FORMULAS.get(2), ag, 1);
            if (k == null) r.put("formulaId", "AG-NA-CL-HCO3");
            ObjectNode i = r.putObject("inputs");
            put(i, "sodium", na);
            if (k != null) put(i, "potassium", k);
            put(i, "chloride", cl);
            put(i, "bicarbonate", hco3);
            out.add(r);
        }
        Input ca = in.get("calcium"), alb = in.get("albumin");
        if (ca != null && alb != null) {
            ObjectNode r = result(FORMULAS.get(3), correctedCalcium(ca.value().doubleValue(), alb.value().doubleValue()), 2);
            put(r.putObject("inputs"), "calcium", ca);
            put((ObjectNode) r.get("inputs"), "albumin", alb);
            out.add(r);
        }
        return out;
    }

    private static ObjectNode result(Formula f, double v, int decimals) {
        ObjectNode r = Json.obj().put("id", f.id()).put("name", f.name()).put("value", BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP)).put("unit", f.unit())
                .put("formulaId", f.formulaId()).put("formulaVersion", f.version()).put("moduleVersion", MODULE_VERSION).put("label", LABEL);
        if (f.entry() != null) r.put("entry", f.entry());
        return r;
    }

    private static void put(ObjectNode inputs, String name, Input i) {
        inputs.putObject(name).put("value", i.value().setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()).put("unit", i.unit());
    }

    /** Template field (definition) that is the target of a derived value, or null → annotation block on the instance. */
    public static JsonNode targetField(JsonNode tpl, String derivedId) {
        Formula f = FORMULAS.stream().filter(x -> x.id().equals(derivedId)).findFirst().orElse(null);
        if (f == null || tpl == null) return null;
        for (JsonNode sec : tpl.path("sections")) for (JsonNode fd : sec.path("fields")) {
            String loinc = fd.at("/code/loinc").asString(null);
            if ((loinc != null && f.loinc().contains(loinc)) || f.fieldIds().contains(fd.path("id").asString())) return fd;
        }
        return null;
    }

    /** Per-configuration switch – default OFF (regulatory: derived calculations need a documented CDS assessment). */
    public static boolean enabled(JsonNode config) { return config != null && config.path("params").path("derivedValues").asBoolean(false); }
}
