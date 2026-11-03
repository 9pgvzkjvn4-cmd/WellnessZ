package io.intimed.engine.terminology;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Terminology & units service (IM-E01-F04 / IM-E01-BL-020): device unit spellings and IEEE 11073 MDC dimension codes →
 * UCUM, and deterministic UCUM conversions. Analyte-specific conversions (mg/dL ↔ mmol/L) are supplied by the driver's
 * semantics (factor per analyte) because they depend on molar mass.
 */
@Component
public class Terminology {
    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("mmhg", "mm[Hg]"), Map.entry("mm[hg]", "mm[Hg]"), Map.entry("mdc_dim_mmhg", "mm[Hg]"), Map.entry("kpa", "kPa"),
            Map.entry("mdc_dim_kilo_pascal", "kPa"), Map.entry("bpm", "/min"), Map.entry("/min", "/min"), Map.entry("1/min", "/min"),
            Map.entry("mdc_dim_beat_per_min", "/min"), Map.entry("mdc_dim_resp_per_min", "/min"), Map.entry("brpm", "/min"),
            Map.entry("%", "%"), Map.entry("mdc_dim_percent", "%"), Map.entry("c", "Cel"), Map.entry("cel", "Cel"), Map.entry("degc", "Cel"),
            Map.entry("°c", "Cel"), Map.entry("mdc_dim_degc", "Cel"), Map.entry("f", "[degF]"), Map.entry("°f", "[degF]"), Map.entry("degf", "[degF]"),
            Map.entry("[degf]", "[degF]"), Map.entry("mdc_dim_fahr", "[degF]"), Map.entry("kg", "kg"), Map.entry("mdc_dim_kilo_g", "kg"),
            Map.entry("lb", "[lb_av]"), Map.entry("lbs", "[lb_av]"), Map.entry("[lb_av]", "[lb_av]"), Map.entry("mdc_dim_lb", "[lb_av]"),
            Map.entry("cm", "cm"), Map.entry("in", "[in_i]"), Map.entry("[in_i]", "[in_i]"), Map.entry("m", "m"), Map.entry("l", "L"),
            Map.entry("mdc_dim_l", "L"), Map.entry("ml", "mL"), Map.entry("l/s", "L/s"), Map.entry("mdc_dim_l_per_sec", "L/s"),
            Map.entry("l/min", "L/min"), Map.entry("ms", "ms"), Map.entry("mdc_dim_milli_sec", "ms"), Map.entry("s", "s"),
            Map.entry("mg/dl", "mg/dL"), Map.entry("mdc_dim_milli_g_per_dl", "mg/dL"), Map.entry("mmol/l", "mmol/L"),
            Map.entry("mdc_dim_milli_mole_per_l", "mmol/L"), Map.entry("g/dl", "g/dL"), Map.entry("g/l", "g/L"), Map.entry("ml/h", "mL/h"),
            Map.entry("mg/h", "mg/h"), Map.entry("ug/kg/min", "ug/kg/min"), Map.entry("mcg/kg/min", "ug/kg/min"), Map.entry("db", "dB"),
            Map.entry("dbhl", "dB"), Map.entry("db hl", "dB"), Map.entry("hz", "Hz"), Map.entry("cmh2o", "cm[H2O]"), Map.entry("cm[h2o]", "cm[H2O]"),
            Map.entry("ppm", "[ppm]"), Map.entry("g/100ml", "g/dL"), Map.entry("mg/l", "mg/L"),
            Map.entry("mgy.cm2", "mGy.cm2"), Map.entry("mgy", "mGy"), Map.entry("ohm", "Ohm"), Map.entry("v", "V"), Map.entry("j", "J"),
            Map.entry("kg/m2", "kg/m2"), Map.entry("1", "1"), Map.entry("{ratio}", "1"), Map.entry("mdc_dim_dimless", "1"), Map.entry("steps", "{steps}"),
            Map.entry("{steps}", "{steps}"), Map.entry("h", "h"), Map.entry("min", "min"), Map.entry("/h", "/h"), Map.entry("lux", "lx"),
            Map.entry("mgcm2", "mGy.cm2"), Map.entry("mmol/mol", "mmol/mol"), Map.entry("mg/g", "mg/g"), Map.entry("kcal", "kcal"),
            Map.entry("ml/min", "mL/min"), Map.entry("mm", "mm"), Map.entry("ng/ml", "ng/mL"), Map.entry("u/l", "U/L"), Map.entry("10*9/l", "10*9/L"),
            Map.entry("ug/dl", "ug/dL"), Map.entry("umol/l", "umol/L"), Map.entry("µmol/l", "umol/L"), Map.entry("mmol/kg", "mmol/kg"), Map.entry("10^9/l", "10*9/L"), Map.entry("10*12/l", "10*12/L"), Map.entry("ph", "[pH]"), Map.entry("[ph]", "[pH]"), Map.entry("deg", "deg"),
            Map.entry("mgy.cm", "mGy.cm"), Map.entry("g/210l", "g/210L"), Map.entry("mdc_dim_milli_l", "mL"), Map.entry("mdc_dim_milli_l_per_hr", "mL/h"), Map.entry("mdc_dim_cm_h2o", "cm[H2O]"));

    /** Linear conversions a*x + b keyed "from>to". */
    private static final Map<String, double[]> LINEAR = Map.ofEntries(
            Map.entry("kPa>mm[Hg]", new double[]{7.50062, 0}), Map.entry("mm[Hg]>kPa", new double[]{0.133322, 0}),
            Map.entry("[degF]>Cel", new double[]{5.0 / 9, -32 * 5.0 / 9}), Map.entry("Cel>[degF]", new double[]{1.8, 32}),
            Map.entry("[lb_av]>kg", new double[]{0.45359237, 0}), Map.entry("kg>[lb_av]", new double[]{2.20462262, 0}),
            Map.entry("[in_i]>cm", new double[]{2.54, 0}), Map.entry("m>cm", new double[]{100, 0}), Map.entry("cm>m", new double[]{0.01, 0}),
            Map.entry("mL>L", new double[]{0.001, 0}), Map.entry("L>mL", new double[]{1000, 0}), Map.entry("s>ms", new double[]{1000, 0}),
            Map.entry("ms>s", new double[]{0.001, 0}), Map.entry("g/L>g/dL", new double[]{0.1, 0}), Map.entry("g/dL>g/L", new double[]{10, 0}),
            Map.entry("h>min", new double[]{60, 0}), Map.entry("min>h", new double[]{1.0 / 60, 0}), Map.entry("L/min>mL/min", new double[]{1000, 0}));

    public String ucum(String deviceUnit) {
        if (deviceUnit == null || deviceUnit.isBlank()) return null;
        String u = SYNONYMS.get(deviceUnit.trim().toLowerCase());
        return u != null ? u : deviceUnit.trim();
    }

    public boolean known(String ucum) { return ucum != null && (SYNONYMS.containsValue(ucum)); }

    /** Converts value from → to; returns empty if no conversion is defined (the router then blocks posting, RR-06). */
    public Optional<BigDecimal> convert(BigDecimal v, String from, String to, Map<String, Double> analyteFactors) {
        if (from == null || to == null || from.equals(to)) return Optional.of(v);
        Double f = analyteFactors == null ? null : analyteFactors.get(from + ">" + to);
        if (f != null) return Optional.of(round(v.multiply(BigDecimal.valueOf(f))));
        double[] ab = LINEAR.get(from + ">" + to);
        if (ab == null) return Optional.empty();
        return Optional.of(round(v.multiply(BigDecimal.valueOf(ab[0])).add(BigDecimal.valueOf(ab[1]))));
    }

    static BigDecimal round(BigDecimal v) {
        BigDecimal r = v.round(new MathContext(6, RoundingMode.HALF_UP));
        return r.scale() > 3 ? r.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros() : r.stripTrailingZeros();
    }

    public Map<String, String> synonyms() { return SYNONYMS; }
}
