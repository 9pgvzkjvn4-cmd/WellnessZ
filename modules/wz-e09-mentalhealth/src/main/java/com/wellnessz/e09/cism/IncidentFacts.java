package com.wellnessz.e09.cism;

import java.time.Instant;

/** The fields of {@code ehs.incident.received.v1} that CISM uses (E09's own reading of the payload; unknown fields ignored). */
public record IncidentFacts(String incidentId, String externalRef, String employeeId, String orgUnitCode, String incidentType, String severity,
                            String incidentClass, String unitCode, String location, String incidentOccurredAt) {

    public Instant occurredAt() {
        try {
            return incidentOccurredAt == null ? null : Instant.parse(incidentOccurredAt);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
