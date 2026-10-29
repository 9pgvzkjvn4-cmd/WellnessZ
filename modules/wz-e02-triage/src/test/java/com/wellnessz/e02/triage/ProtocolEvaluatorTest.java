package com.wellnessz.e02.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.model.Urgency;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ProtocolEvaluatorTest {

    static JsonNode load(String code) throws Exception {
        try (InputStream in = ProtocolEvaluatorTest.class.getResourceAsStream("/triage/protocols/" + code + ".json")) {
            return JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void redFlagIsHardStop() throws Exception {
        var r = ProtocolEvaluator.evaluate(load("MSK"), Map.of("q1", true));
        assertThat(r.urgency()).isEqualTo(Urgency.EMERGENCY);
        assertThat(r.redFlags()).containsExactly("q1");
    }

    @Test
    void numericRulesAndRanges() throws Exception {
        JsonNode msk = load("MSK");
        assertThat(ProtocolEvaluator.evaluate(msk, Map.of("q1", false, "q2", false, "q3", 9)).urgency()).isEqualTo(Urgency.URGENT);
        assertThat(ProtocolEvaluator.evaluate(msk, Map.of("q1", false, "q2", false, "q3", 5, "q4", false)).urgency())
                .isEqualTo(Urgency.SEMI_URGENT);
        JsonNode heat = load("HEAT");
        assertThat(ProtocolEvaluator.evaluate(heat, Map.of("q1", false, "q2", 38.5)).urgency()).isEqualTo(Urgency.SEMI_URGENT);
        assertThat(ProtocolEvaluator.evaluate(heat, Map.of("q1", false, "q2", 37, "q3", true)).urgency()).isEqualTo(Urgency.ADVICE);
    }

    @Test
    void selectQuestionsAndValidation() throws Exception {
        JsonNode fatigue = load("CREW_FATIGUE");
        assertThat(ProtocolEvaluator.evaluate(fatigue, Map.of("q1", false, "q3", "RECURRENT")).urgency()).isEqualTo(Urgency.SEMI_URGENT);
        assertThatThrownBy(() -> ProtocolEvaluator.evaluate(fatigue, Map.of("q1", false, "q3", "BOGUS")))
                .isInstanceOf(ProtocolEvaluator.IncompleteException.class);
        assertThatThrownBy(() -> ProtocolEvaluator.evaluate(fatigue, Map.of("q1", true, "q2", 9)))
                .isInstanceOf(ProtocolEvaluator.IncompleteException.class); // out of range 1–7
        assertThatThrownBy(() -> ProtocolEvaluator.evaluate(load("MSK"), Map.of("q1", false)))
                .isInstanceOf(ProtocolEvaluator.IncompleteException.class);
    }

    @Test
    void ruleGrammar() {
        assertThat(ProtocolEvaluator.matches(">=8", 8)).isTrue();
        assertThat(ProtocolEvaluator.matches("4..7", 7)).isTrue();
        assertThat(ProtocolEvaluator.matches("4-7", 8)).isFalse();
        assertThat(ProtocolEvaluator.matches("!=3", 4)).isTrue();
        assertThat(ProtocolEvaluator.matches("==true", "yes")).isTrue();
        assertThat(ProtocolEvaluator.matches("5", 5)).isTrue();
    }

    @Test
    void allSeededProtocolsHaveAllOutcomes() throws Exception {
        for (String code : new String[]{"MSK", "LACERATION_BURN", "EYE", "CHEMICAL", "HEAT", "RESPIRATORY", "PSYCHOLOGICAL",
                "INFECTIOUS_GI", "CARDIAC", "CREW_FATIGUE", "HEAD_INJURY", "BODY_FLUID", "FUME_EVENT"}) {
            JsonNode p = load(code);
            for (Urgency u : Urgency.values()) {
                assertThat(p.path("outcomes").has(u.name())).as(code + " " + u).isTrue();
            }
        }
    }
}
