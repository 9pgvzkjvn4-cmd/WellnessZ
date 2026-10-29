package com.wellnessz.casekernel.model;

/** Case pathway selected at intake / triage (E02 scope: case typing and pathway selection). */
public enum CaseType {
    INJURY, ILLNESS, EXPOSURE, FITNESS, SURVEILLANCE_FOLLOW_UP, MENTAL_HEALTH, ABSENCE, CLAIM, SUBSTANCE, OTHER;

    public static CaseType defaultFor(CaseCategory category) {
        return switch (category) {
            case INJURY -> INJURY;
            case ILLNESS -> ILLNESS;
            case DECREASE_IN_FITNESS -> FITNESS;
            case PSYCHOLOGICAL -> MENTAL_HEALTH;
            case SUBSTANCE -> SUBSTANCE;
            case EXPOSURE -> EXPOSURE;
            case OTHER -> OTHER;
        };
    }
}
