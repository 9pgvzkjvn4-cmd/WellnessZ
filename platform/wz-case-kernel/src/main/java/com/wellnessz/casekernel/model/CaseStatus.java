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
    INTAKE, TRIAGE, ASSESSMENT, FITNESS_REVIEW, TREATMENT, CLAIMS, MONITORING, CLOSED,
    /** Duplicate merged into another case (E01-F06); terminal. */
    MERGED;

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = Map.of(
            INTAKE, EnumSet.of(TRIAGE, CLOSED, MERGED),
            TRIAGE, EnumSet.of(ASSESSMENT, FITNESS_REVIEW, TREATMENT, MONITORING, CLOSED, MERGED),
            ASSESSMENT, EnumSet.of(FITNESS_REVIEW, TREATMENT, MONITORING, CLOSED),
            FITNESS_REVIEW, EnumSet.of(TREATMENT, MONITORING, CLAIMS, CLOSED),
            TREATMENT, EnumSet.of(FITNESS_REVIEW, CLAIMS, MONITORING, CLOSED),
            CLAIMS, EnumSet.of(TREATMENT, MONITORING, CLOSED),
            MONITORING, EnumSet.of(FITNESS_REVIEW, TREATMENT, CLOSED),
            CLOSED, EnumSet.of(TRIAGE),
            MERGED, EnumSet.noneOf(CaseStatus.class));

    public boolean canTransitionTo(CaseStatus next) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(next);
    }
}
