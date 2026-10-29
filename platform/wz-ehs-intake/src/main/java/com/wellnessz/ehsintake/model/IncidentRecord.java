package com.wellnessz.ehsintake.model;

import java.time.Instant;

/** Normalised incident as read from the EHS feed (webhook, Excel or mailbox), before persistence. */
public record IncidentRecord(String sourceSystem, String externalRef, Instant occurredAt, String location, String employeeId,
                             String employeeName, String orgUnitCode, String incidentType, String severity, boolean injury,
                             String description, String incidentClass, String bodyPart, Boolean lostTime, String exposureType,
                             String unitCode, String shift, String reporterEmail) {

    /** Base-framework shape (Excel / mailbox without increment-1 columns). */
    public IncidentRecord(String sourceSystem, String externalRef, Instant occurredAt, String location, String employeeId,
                          String employeeName, String orgUnitCode, String incidentType, String severity, boolean injury,
                          String description) {
        this(sourceSystem, externalRef, occurredAt, location, employeeId, employeeName, orgUnitCode, incidentType, severity, injury,
                description, null, null, null, null, null, null, null);
    }
}
