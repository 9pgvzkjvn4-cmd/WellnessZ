package com.wellnessz.casekernel.model;

/** Safety-critical and licensed role categories (E02-HLR-02). */
public enum SafetyCategory {
    FLIGHT_CREW, CABIN_CREW, CERTIFYING_ENGINEER, ATC, AIRSIDE_DRIVER, FORKLIFT, FOOD_HANDLER, WORKING_AT_HEIGHT;

    public static SafetyCategory parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isAeromedical() {
        return this == FLIGHT_CREW || this == CABIN_CREW || this == ATC;
    }
}
