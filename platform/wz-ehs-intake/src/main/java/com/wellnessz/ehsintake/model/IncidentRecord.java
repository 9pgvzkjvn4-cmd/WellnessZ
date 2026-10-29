package com.wellnessz.ehsintake.model;

import java.time.Instant;

/** Normalised incident row as read from the EHS feed, before persistence. */
public record IncidentRecord(String sourceSystem, String externalRef, Instant occurredAt, String location, String employeeId,
                             String employeeName, String orgUnitCode, String incidentType, String severity, boolean injury,
                             String description) {
}
