package com.wellnessz.e01.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.Urgency;

class RuleEngineTest {

    static IntakeRule rule(int priority, String cls, Boolean lostTime, String minSeverity, RuleAction action) {
        IntakeRule r = new IntakeRule("test", Instant.now());
        r.update("r" + priority, priority, true, cls, minSeverity, lostTime, null, null, null, null, action, CaseType.INJURY,
                Urgency.ROUTINE, "test", Instant.now());
        return r;
    }

    static IncidentFacts facts(String cls, Boolean lostTime, String severity) {
        return new IncidentFacts("00000000-0000-0000-0000-000000000001", "INC-1", "E1", null, null, null, severity, false, cls,
                "HAND", lostTime, null, "CAT", null, null, null, null);
    }

    @Test
    void lowestPriorityMatchingRuleWins() {
        List<IntakeRule> rules = List.of(rule(5, null, true, null, RuleAction.AUTO_CREATE), rule(80, "NEAR_MISS", null, null, RuleAction.IGNORE),
                rule(95, null, null, "MEDIUM", RuleAction.SUGGEST));
        assertThat(RuleEngine.firstMatch(rules, facts("NEAR_MISS", true, "LOW")).orElseThrow().getAction()).isEqualTo(RuleAction.AUTO_CREATE);
        assertThat(RuleEngine.firstMatch(rules, facts("NEAR_MISS", false, "LOW")).orElseThrow().getAction()).isEqualTo(RuleAction.IGNORE);
        assertThat(RuleEngine.firstMatch(rules, facts("OTHER", null, "HIGH")).orElseThrow().getAction()).isEqualTo(RuleAction.SUGGEST);
        assertThat(RuleEngine.firstMatch(rules, facts("OTHER", null, "LOW"))).isEmpty();
    }

    @Test
    void categoryDerivedFromIncidentClass() {
        assertThat(RuleEngine.categoryOf(facts("FUME_EVENT", null, null), null)).isEqualTo(CaseCategory.EXPOSURE);
        assertThat(RuleEngine.categoryOf(facts("HEAT_ILLNESS", null, null), null)).isEqualTo(CaseCategory.ILLNESS);
        assertThat(RuleEngine.categoryOf(facts("X", null, null), CaseType.INJURY)).isEqualTo(CaseCategory.INJURY);
    }
}
