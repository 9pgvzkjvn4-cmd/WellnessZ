package io.intimed;

import io.intimed.clinical.DeltaCheck;
import io.intimed.clinical.Demographics;
import io.intimed.clinical.DerivedValues;
import io.intimed.clinical.IntervalGovernance;
import io.intimed.clinical.News2;
import io.intimed.common.ApiException;
import io.intimed.intelligence.CohortSimulator;
import io.intimed.clinical.PlatformCategories;
import io.intimed.clinical.ReferenceRanges;
import io.intimed.common.Json;
import io.intimed.engine.terminology.Terminology;
import io.intimed.intelligence.ValidationEngine;
import io.intimed.templates.TemplateAdapters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** IntiMed 2.0 unit tests: reference-interval partitioning, cohort expectations, interpretation, layered validation, adapters. */
class ClinicalIntelligenceTests {
    private final ReferenceRanges ranges = new ReferenceRanges();
    private final PlatformCategories categories = new PlatformCategories();
    private final TemplateAdapters adapters = new TemplateAdapters();
    private final ValidationEngine validation = new ValidationEngine(ranges, categories, new Terminology(), adapters);

    private String flag(String entry, String value, JsonNode demo) {
        ReferenceRanges.Resolved r = ranges.resolve(ranges.entry(entry), Demographics.of(demo));
        ReferenceRanges.Interpretation i = ranges.interpret(new BigDecimal(value), r);
        return i == null ? null : i.code();
    }

    private static ObjectNode demo(double age, String sex) { return Json.obj().put("ageYears", age).put("sex", sex); }

    @Test
    void ageSexPregnancyAndContextPartitionTheInterval() {
        assertThat(flag("heart-rate", "140", demo(0.5, "male"))).isEqualTo("N");
        assertThat(flag("heart-rate", "140", demo(8, "male"))).isEqualTo("H");
        assertThat(flag("heart-rate", "140", demo(40, "male"))).isIn("H", "HH");
        assertThat(flag("haemoglobin", "10.8", demo(35, "female"))).isEqualTo("L");
        assertThat(flag("haemoglobin", "10.8", demo(29, "female").put("pregnant", true).put("trimester", 2))).isEqualTo("N");
        assertThat(flag("spo2", "92", demo(30, "male").put("siteAltitudeM", 0))).isEqualTo("L");
        assertThat(flag("spo2", "92", demo(30, "male").put("siteAltitudeM", 3000))).isEqualTo("N");
        assertThat(flag("qtc", "455", demo(50, "male"))).isEqualTo("H");
        assertThat(flag("qtc", "455", demo(50, "female"))).isEqualTo("N");
        assertThat(flag("ahi", "3", demo(7, "female"))).isEqualTo("H");
        assertThat(flag("ahi", "3", demo(40, "female"))).isEqualTo("N");
        // grade (ACC/AHA 2017) alongside the flag
        ReferenceRanges.Resolved bp = ranges.resolve(ranges.entry("systolic"), Demographics.of(demo(45, "male")));
        assertThat(ranges.interpret(new BigDecimal("135"), bp).grade()).containsIgnoringCase("stage 1");
        assertThat(ranges.ageBand(0.01)).containsIgnoringCase("neonate");
    }

    static List<Object[]> cohortExpectations() {
        List<Object[]> out = new ArrayList<>();
        for (JsonNode p : Json.resource("simulator/profiles.json"))
            for (var e : p.path("expected").properties())
                out.add(new Object[]{p.get("id").asString(), e.getKey(), p.at("/concepts/" + e.getKey().replace("/", "~1")).asString(), e.getValue().asString(), p.get("demographics")});
        return out;
    }

    @ParameterizedTest(name = "{0} {1}={2} → {3}")
    @MethodSource("cohortExpectations")
    void everyScriptedCohortProducesItsExpectedFlag(String profile, String concept, String value, String expected, JsonNode demographics) {
        String entry = concept.contains("@") ? concept.substring(0, concept.indexOf('@')) : concept;
        assertThat(flag(entry, value, demographics)).as(profile + " " + concept).isEqualTo(expected);
    }

    @Test
    void everyKnowledgeBaseEntryHasASourceAndAnAdultOrAllAgeInterval() {
        for (JsonNode e : ranges.entries()) {
            assertThat(e.path("source").asString()).as(e.get("id").asString()).isNotBlank();
            assertThat(ranges.resolve(e, Demographics.of(demo(40, "female"))).gap() && !e.has("gapNote")).as(e.get("id").asString()).isFalse();
        }
        assertThat(ranges.entries().size()).isGreaterThanOrEqualTo(30);
        assertThat(categories.all().size()).isEqualTo(8);
    }

    private ObjectNode field(String value, String unit) { return Json.obj().put("value", new BigDecimal(value)).put("unit", unit); }

    @Test
    void layeredValidationCatchesCrossFieldUnitConsentAndLabRangeIssues() {
        JsonNode tpl02 = Json.resource("templates/TPL-02.json");
        ObjectNode f = Json.obj();
        f.set("hr", field("88", "/min"));
        f.set("spo2", field("97", "%"));
        f.set("rr", field("16", "/min"));
        f.set("nibp_sys", field("70", "mm[Hg]"));
        f.set("nibp_dia", field("90", "mm[Hg]"));
        f.set("temp", field("98.6", "[degF]"));
        ObjectNode v = validation.validate(tpl02, f, "ACUTE_EHR", Demographics.of(demo(40, "male")), null);
        assertThat(v.get("verdict").asString()).isEqualTo("FAIL");
        assertThat(v.toString()).contains("systolic 70.0 ≤ diastolic 90.0").contains("unit [degF] ≠ template unit Cel");
        // FHIR + HL7 outputs carry interpretation and OBX-7/OBX-8
        assertThat(v.at("/outputs/FHIR").toString()).contains("referenceRange").contains("v3-ObservationInterpretation");
        assertThat(v.at("/outputs/HL7V2").asString()).containsPattern("\\|88\\|/min\\^\\^UCUM\\|\\d+-\\d+\\|N\\|");
        // RPM requires consent
        JsonNode tpl13 = Json.resource("templates/TPL-13.json");
        ObjectNode r = Json.obj();
        r.set("sys", field("128", "mm[Hg]"));
        r.set("dia", field("82", "mm[Hg]"));
        assertThat(validation.validate(tpl13, r.deepCopy(), "RPM_HOME", Demographics.of(demo(60, "female")), null).toString()).contains("no active consent");
        assertThat(validation.validate(tpl13, r.deepCopy(), "RPM_HOME", Demographics.of(demo(60, "female")), Json.obj().put("consent", true)).get("errors").asInt()).isZero();
        // laboratory category requires a reference interval on every numeric result
        JsonNode tpl06 = Json.resource("templates/TPL-06.json");
        ObjectNode lab = Json.obj();
        lab.set("glucose", field("5.1", "mmol/L"));
        lab.set("creatinine", field("80", "umol/L"));
        ObjectNode lv = validation.validate(tpl06, lab, "LIS", Demographics.of(demo(50, "female")), null);
        assertThat(lv.toString()).doesNotContain("without reference interval");
        assertThat(lv.at("/fields").toString()).contains("\"entry\":\"creatinine\"");
    }

    // ---------------------------------------------------------------- IntiMed 2.1 (deferred items)
    /** IM-E14-BL-010: CKD-EPI 2021 race-free – hand-computed: Scr 88 µmol/L = 0.9955 mg/dL; female 50 y → 142 × (0.9955/0.7)^−1.2 × 0.9938^50 × 1.012 ≈ 69.0. */
    @Test
    void egfrCkdEpi2021KnownValuesAndKdigoGrades() {
        assertThat(DerivedValues.egfrCkdEpi2021(88, 50.0, "female")).isCloseTo(69.0, within(0.5));
        assertThat(DerivedValues.egfrCkdEpi2021(88, 50.0, "male")).isCloseTo(92.2, within(0.5));
        // below κ: min term with α (female 30 y, Scr 53 µmol/L = 0.60 mg/dL) → 142 × (0.857)^−0.241 × 0.9938^30 × 1.012 ≈ 124.1
        assertThat(DerivedValues.egfrCkdEpi2021(53, 30.0, "female")).isCloseTo(124.1, within(0.6));
        assertThat(DerivedValues.egfrCkdEpi2021(88, 12.0, "female")).as("paediatric: not validated").isNull();
        assertThat(DerivedValues.egfrCkdEpi2021(88, 50.0, null)).as("sex unknown").isNull();
        ReferenceRanges.Resolved r = ranges.resolve(ranges.entry("egfr"), Demographics.of(demo(50, "female")));
        assertThat(ranges.interpret(new BigDecimal("69"), r).grade()).startsWith("G2");
        assertThat(ranges.interpret(new BigDecimal("12"), r).code()).isEqualTo("LL");
        assertThat(ranges.interpret(new BigDecimal("12"), r).grade()).startsWith("G5");
        assertThat(ranges.interpret(new BigDecimal("50"), r).grade()).startsWith("G3a");
    }

    @Test
    void derivedValuesCarryFormulaVersionInputsAndLabel() {
        var ms = Json.arr();
        ms.addObject().put("key", "creatinine").put("value", new BigDecimal("1.0")).put("unit", "mg/dL").putObject("code").put("loinc", "2160-0");
        ms.addObject().put("key", "weight").put("value", new BigDecimal("80")).put("unit", "kg");
        ms.addObject().put("key", "height").put("value", new BigDecimal("180")).put("unit", "cm");
        ms.addObject().put("key", "na").put("value", new BigDecimal("140")).put("unit", "mmol/L");
        ms.addObject().put("key", "k").put("value", new BigDecimal("4.0")).put("unit", "mmol/L");
        ms.addObject().put("key", "cl").put("value", new BigDecimal("104")).put("unit", "mmol/L");
        ms.addObject().put("key", "hco3").put("value", new BigDecimal("24")).put("unit", "mmol/L");
        ms.addObject().put("key", "ca").put("value", new BigDecimal("2.10")).put("unit", "mmol/L");
        ms.addObject().put("key", "albumin").put("value", new BigDecimal("30")).put("unit", "g/L");
        var out = new DerivedValues().derive(ms, Demographics.of(demo(60, "male")));
        java.util.Map<String, JsonNode> byId = new java.util.HashMap<>();
        out.forEach(d -> byId.put(d.get("id").asString(), d));
        assertThat(byId).containsKeys("egfr", "bmi", "anion-gap", "corrected-calcium");
        assertThat(byId.get("bmi").get("value").asDouble()).isEqualTo(24.7);
        assertThat(byId.get("anion-gap").get("value").asDouble()).isEqualTo(16.0);
        assertThat(byId.get("corrected-calcium").get("value").asDouble()).isEqualTo(2.30);
        assertThat(byId.get("egfr").at("/inputs/creatinine/value").asDouble()).isCloseTo(88.4, within(0.01));
        for (JsonNode d : out) {
            assertThat(d.get("label").asString()).isEqualTo("derived – verify");
            assertThat(d.get("formulaId").asString()).isNotBlank();
            assertThat(d.get("formulaVersion").asString()).isEqualTo("1.0.0");
            assertThat(d.get("inputs").size()).isPositive();
        }
        // regulatory switch: OFF unless the configuration enables it
        assertThat(DerivedValues.enabled(Json.obj())).isFalse();
        assertThat(DerivedValues.enabled(Json.obj().set("params", Json.obj().put("derivedValues", true)))).isTrue();
        // units that are not understood are never guessed
        var bad = Json.arr();
        bad.addObject().put("key", "creatinine").put("value", new BigDecimal("88")).put("unit", "mmol/m3");
        assertThat(new DerivedValues().derive(bad, Demographics.of(demo(50, "female")))).isEmpty();
    }

    @Test
    void deltaThresholdsAbsoluteRelativeAndWindow() {
        Instant t0 = Instant.parse("2026-11-01T08:00:00Z");
        JsonNode k = ranges.entry("potassium"), cr = ranges.entry("creatinine"), na = ranges.entry("sodium"), hb = ranges.entry("haemoglobin"), glu = ranges.entry("glucose");
        assertThat(k.at("/delta/abs").asDouble()).isEqualTo(1.0);
        assertThat(na.at("/delta/abs").asDouble()).isEqualTo(8.0);
        assertThat(hb.at("/delta/abs").asDouble()).isEqualTo(2.0);
        assertThat(cr.at("/delta/pct").asDouble()).isEqualTo(50.0);
        assertThat(glu.at("/delta/pct").asDouble()).isEqualTo(50.0);
        assertThat(DeltaCheck.check(k, new BigDecimal("4.0"), t0, new BigDecimal("5.2"), t0.plusSeconds(3600), 48).flagged()).isTrue();
        assertThat(DeltaCheck.check(k, new BigDecimal("4.0"), t0, new BigDecimal("4.8"), t0.plusSeconds(3600), 48).flagged()).isFalse();
        assertThat(DeltaCheck.check(cr, new BigDecimal("80"), t0, new BigDecimal("130"), t0.plusSeconds(86400), 168).flagged()).as("62.5 % > 50 %").isTrue();
        assertThat(DeltaCheck.check(cr, new BigDecimal("80"), t0, new BigDecimal("110"), t0.plusSeconds(86400), 168).flagged()).as("37.5 %").isFalse();
        assertThat(DeltaCheck.check(k, new BigDecimal("4.0"), t0, new BigDecimal("6.0"), t0.plusSeconds(49 * 3600), 48)).as("outside window").isNull();
        assertThat(DeltaCheck.check(ranges.entry("heart-rate"), new BigDecimal("60"), t0, new BigDecimal("150"), t0.plusSeconds(60), 48)).as("no threshold").isNull();
        assertThat(DeltaCheck.windowHours(k, Json.obj().set("params", Json.obj().put("deltaWindowHours", 12)))).isEqualTo(12);
        // default ON only when reference-range flagging is ON
        assertThat(DeltaCheck.enabled(Json.obj())).isTrue();
        assertThat(DeltaCheck.enabled(Json.obj().set("params", Json.obj().put("referenceRangeFlagging", false)))).isFalse();
        assertThat(DeltaCheck.enabled(Json.obj().set("params", Json.obj().put("deltaChecks", false)))).isFalse();
    }

    @Test
    void ep28TransferenceVerificationPassesAtTwoOfTwentyAndFailsAtThree() {
        List<BigDecimal> ok = new ArrayList<>();
        for (int i = 0; i < 18; i++) ok.add(new BigDecimal("4.0"));
        ok.add(new BigDecimal("3.2"));
        ok.add(new BigDecimal("5.5"));
        ObjectNode pass = IntervalGovernance.ep28(new BigDecimal("3.4"), new BigDecimal("5.0"), ok);
        assertThat(pass.get("pass").asBoolean()).isTrue();
        assertThat(pass.get("outside").asInt()).isEqualTo(2);
        assertThat(pass.get("allowedOutside").asInt()).isEqualTo(2);
        List<BigDecimal> bad = new ArrayList<>(ok);
        bad.set(0, new BigDecimal("5.1"));
        assertThat(IntervalGovernance.ep28(new BigDecimal("3.4"), new BigDecimal("5.0"), bad).get("pass").asBoolean()).isFalse();
        assertThatThrownBy(() -> IntervalGovernance.ep28(new BigDecimal("3.4"), new BigDecimal("5.0"), ok.subList(0, 19))).isInstanceOf(ApiException.class).hasMessageContaining("20");
        // tenant overlay replaces the default partitions and is cited as the source
        ranges.overlaySource(t -> "t-x".equals(t) ? Json.parse("{\"id\":\"ivo-3\",\"version\":3,\"source\":\"Lab X\",\"entries\":{\"potassium\":[{\"ageFrom\":18,\"ageTo\":200,\"sex\":\"any\",\"low\":3.4,\"high\":4.8}]}}") : null);
        try {
            ObjectNode fv = Json.obj().put("value", new BigDecimal("4.9"));
            ranges.annotate(fv, Json.obj().set("code", Json.obj().put("loinc", "2823-3")), "k", Demographics.of(demo(50, "male")), "t-x");
            assertThat(fv.at("/interpretation/code").asString()).isEqualTo("H");
            assertThat(fv.at("/referenceRange/source").asString()).startsWith("tenant overlay v3");
            ObjectNode other = Json.obj().put("value", new BigDecimal("4.9"));
            ranges.annotate(other, Json.obj().set("code", Json.obj().put("loinc", "2823-3")), "k", Demographics.of(demo(50, "male")), "t-y");
            assertThat(other.at("/interpretation/code").asString()).isEqualTo("N");
        } finally { ranges.overlaySource(null); }
    }

    @Test
    void trajectoryInterpolationIsPiecewiseLinearAndNews2Aggregates() {
        JsonNode sepsis = null;
        for (JsonNode p : Json.resource("simulator/profiles.json")) if ("traj-sepsis-6h".equals(p.get("id").asString())) sepsis = p;
        assertThat(sepsis).isNotNull();
        long trajectories = 0;
        for (JsonNode p : Json.resource("simulator/profiles.json")) if (p.has("trajectory")) trajectories++;
        assertThat(trajectories).isEqualTo(3);
        Map<String, Double> at60 = CohortSimulator.interpolate(sepsis.get("trajectory"), 60);
        assertThat(at60.get("heart-rate")).isCloseTo(100.0, within(1e-9));
        assertThat(at60.get("spo2")).isCloseTo(95.0, within(1e-9));
        assertThat(CohortSimulator.interpolate(sepsis.get("trajectory"), 0).get("body-temp")).isEqualTo(37.6);
        assertThat(CohortSimulator.interpolate(sepsis.get("trajectory"), 999).get("heart-rate")).as("held after last keyframe").isEqualTo(134.0);
        assertThat(CohortSimulator.duration(sepsis.get("trajectory"))).isEqualTo(360);
        ObjectNode start = News2.score(Map.of("resp-rate", 18.0, "spo2", 96.0, "systolic", 124.0, "heart-rate", 92.0, "body-temp", 37.6));
        assertThat(start.get("score").asInt()).isEqualTo(1);
        ObjectNode end = News2.score(Map.of("resp-rate", 30.0, "spo2", 89.0, "systolic", 86.0, "heart-rate", 134.0, "body-temp", 39.3));
        assertThat(end.get("score").asInt()).isEqualTo(14);
        assertThat(end.get("band").asString()).isEqualTo("high");
        assertThat(News2.score(Map.of("heart-rate", 80.0)).get("partial").asBoolean()).isTrue();
    }

    @Test
    void occupationalRegulatoryThresholdsAreGradedNotAlarmed() {
        JsonNode tpl20 = Json.resource("templates/TPL-20.json");
        ObjectNode f = Json.obj();
        f.set("brac", field("0.052", "g/210L"));
        ObjectNode v = validation.validate(tpl20, f, "OCC_HEALTH", Demographics.of(demo(41, "male")), null);
        assertThat(v.at("/fields/0/interpretation/code").asString("")).isIn("HH", "");
        assertThat(v.toString()).contains("49 CFR 382.201");
        assertThat(v.get("criticalHandling").asString()).isNotBlank();
    }
}
