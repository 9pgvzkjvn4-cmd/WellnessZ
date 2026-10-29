package com.wellnessz.ehsintake.model;

import com.wellnessz.common.events.DomainEvent;

/** {@code ehs.incident.received.v1} – consumed by the case kernel's incident-to-case rules (E01). */
public record IncidentReceived(String incidentId, String externalRef, String employeeId, String employeeName,
                               String orgUnitCode, String incidentType, String severity, boolean injury) implements DomainEvent {

    @Override
    public String eventType() {
        return "ehs.incident.received.v1";
    }

    @Override
    public String aggregateType() {
        return "EHS_INCIDENT";
    }

    @Override
    public String aggregateId() {
        return incidentId;
    }
}
