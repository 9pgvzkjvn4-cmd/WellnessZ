package io.intimed;

import io.intimed.clinical.Demographics;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
