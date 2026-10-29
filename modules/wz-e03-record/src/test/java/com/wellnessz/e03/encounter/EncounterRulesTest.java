package com.wellnessz.e03.encounter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class EncounterRulesTest {

    static EncounterTemplates.Definition template(String type) throws Exception {
        try (InputStream in = EncounterRulesTest.class.getResourceAsStream("/encounter-templates/" + type + ".json")) {
            return JsonMapper.builder().build().readValue(new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    EncounterTemplates.Definition.class);
        }
    }

    @Test
    void allEightTemplatesShipAsVersionedDraftsWithASoapSection() throws Exception {
        for (String type : EncounterTemplates.TYPES) {
            EncounterTemplates.Definition t = template(type);
            assertThat(t.type()).isEqualTo(type);
            assertThat(t.version()).isEqualTo(1);
            assertThat(t.sections()).extracting(EncounterTemplates.Section::key).contains("soap");
        }
        assertThat(template("INJURY").bodyMapRequired()).isTrue();
        assertThat(template("INJURY").requiresDiagnosis()).isTrue();
        assertThat(template("MENTAL_HEALTH").requiresDiagnosis()).isTrue();
        assertThat(template("FITNESS_ASSESSMENT").fitnessStandardPrompt()).isTrue();
        assertThat(template("CREW_AEROMEDICAL").fitnessStandardPrompt()).isTrue();
        assertThat(template("FOOD_HANDLER").fitnessStandardPrompt()).isTrue();
    }

    @Test
    void mandatoryFieldsAreCheckedAgainstAnswersAndSoap() throws Exception {
        EncounterTemplates.Definition injury = template("INJURY");
        List<String> missing = ClinicalEncounterService.missingFields(injury, Map.of(), Map.of());
        assertThat(missing).contains("incidentDate", "workRelated", "workStatus", "soap.subjective", "soap.assessment", "soap.plan")
                .doesNotContain("activity", "soap.objective");
        Map<String, Object> answers = new HashMap<>(Map.of("incidentDate", "2026-10-29", "workRelated", "YES", "workStatus", "OFF_WORK"));
        Map<String, Object> soap = Map.of("subjective", "s", "assessment", "a", "plan", " ");
        assertThat(ClinicalEncounterService.missingFields(injury, answers, soap)).containsExactly("soap.plan");
        EncounterTemplates.Definition surveillance = template("SURVEILLANCE_EXAM");
        assertThat(ClinicalEncounterService.missingFields(surveillance, Map.of("hazards", List.of(), "surveillanceOutcome", "NO_CHANGE"),
                Map.of("assessment", "a", "plan", "p"))).containsExactly("hazards");
    }

    @Test
    void defaultFitnessStandardFollowsTemplateAndSafetyCategory() {
        assertThat(EncounterCds.defaultStandard("FOOD_HANDLER", null)).isEqualTo("FOOD_HANDLER");
        assertThat(EncounterCds.defaultStandard("CREW_AEROMEDICAL", "FLIGHT_CREW")).isEqualTo("CAA_CLASS1");
        assertThat(EncounterCds.defaultStandard("CREW_AEROMEDICAL", "CABIN_CREW")).isEqualTo("CABIN_CREW");
        assertThat(EncounterCds.defaultStandard("FITNESS_ASSESSMENT", "AIRSIDE_DRIVER")).isEqualTo("AIRSIDE_DRIVING");
        assertThat(EncounterCds.defaultStandard("FITNESS_ASSESSMENT", "WORKING_AT_HEIGHT")).isEqualTo("WORKING_AT_HEIGHT");
        assertThat(EncounterCds.defaultStandard("FITNESS_ASSESSMENT", null)).isEqualTo("GENERAL_DUTIES");
    }

    @Test
    void signatureHashIsSha256Hex() {
        assertThat(ClinicalEncounterService.sha256("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
