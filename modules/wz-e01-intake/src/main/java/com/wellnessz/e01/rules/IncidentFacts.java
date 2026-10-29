package com.wellnessz.e01.rules;

import java.time.Instant;

/** The fields of {@code ehs.incident.received.v1} that rules and case creation use. */
public record IncidentFacts(String incidentId, String externalRef, String employeeId, String employeeName, String orgUnitCode,
                            String incidentType, String severity, boolean injury, String incidentClass, String bodyPart,
                            Boolean lostTime, String exposureType, String unitCode, String location, String shift,
                            String incidentOccurredAt, String reporterEmail) {

    public Instant occurredAt() {
        try {
            return incidentOccurredAt == null ? null : Instant.parse(incidentOccurredAt);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
