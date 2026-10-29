package com.wellnessz.e01.rules;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseType;

/** Evaluates incidents against the rule table (first matching active rule by priority wins). */
@Component
public class RuleEngine {

    private final IntakeRuleRepository rules;

    public RuleEngine(IntakeRuleRepository rules) {
        this.rules = rules;
    }

    public Optional<IntakeRule> match(IncidentFacts facts) {
        return firstMatch(rules.findByActiveTrueOrderByPriorityAscIdAsc(), facts);
    }

    static Optional<IntakeRule> firstMatch(List<IntakeRule> ordered, IncidentFacts facts) {
        return ordered.stream().filter(r -> r.matches(facts)).findFirst();
    }

    /** Presenting category derived from the incident class (falls back to the base-framework injury flag). */
    public static CaseCategory categoryOf(IncidentFacts f, CaseType ruleType) {
        if (ruleType != null) {
            return switch (ruleType) {
                case EXPOSURE -> CaseCategory.EXPOSURE;
                case ILLNESS -> CaseCategory.ILLNESS;
                case INJURY -> CaseCategory.INJURY;
                case MENTAL_HEALTH -> CaseCategory.PSYCHOLOGICAL;
                case SUBSTANCE -> CaseCategory.SUBSTANCE;
                case FITNESS -> CaseCategory.DECREASE_IN_FITNESS;
                default -> CaseCategory.OTHER;
            };
        }
        String c = f.incidentClass() == null ? "" : f.incidentClass();
        if (c.contains("EXPOSURE") || c.equals("FUME_EVENT")) {
            return CaseCategory.EXPOSURE;
        }
        if (c.contains("ILLNESS")) {
            return CaseCategory.ILLNESS;
        }
        return f.injury() || c.contains("INJURY") ? CaseCategory.INJURY : CaseCategory.OTHER;
    }
}
