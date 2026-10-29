package com.wellnessz.casekernel.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Case pathway from the logical architecture: Intake → Triage → Assessment &amp; record → Fitness / RTW →
 * Treatment &amp; referrals → Claims → Closure. Allowed transitions are the base state machine; workflow modules
 * (E02) may narrow them further via configuration.
 */
public enum CaseStatus {
    INTAKE, TRIAGE, ASSESSMENT, FITNESS_REVIEW, TREATMENT, CLAIMS, MONITORING, CLOSED;

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = Map.of(
            INTAKE, EnumSet.of(TRIAGE, CLOSED),
            TRIAGE, EnumSet.of(ASSESSMENT, CLOSED),
            ASSESSMENT, EnumSet.of(FITNESS_REVIEW, TREATMENT, MONITORING, CLOSED),
            FITNESS_REVIEW, EnumSet.of(TREATMENT, MONITORING, CLAIMS, CLOSED),
            TREATMENT, EnumSet.of(FITNESS_REVIEW, CLAIMS, MONITORING, CLOSED),
            CLAIMS, EnumSet.of(TREATMENT, MONITORING, CLOSED),
            MONITORING, EnumSet.of(FITNESS_REVIEW, TREATMENT, CLOSED),
            CLOSED, EnumSet.of(TRIAGE));

    public boolean canTransitionTo(CaseStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
