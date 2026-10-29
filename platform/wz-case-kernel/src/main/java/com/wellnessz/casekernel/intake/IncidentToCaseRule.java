package com.wellnessz.casekernel.intake;

import java.util.Optional;

import com.wellnessz.casekernel.model.CaseCategory;

/**
 * Incident-to-case decision (seed of module E01's configurable rule engine). Implementations are Spring beans;
 * the first rule returning a category opens a case.
 */
public interface IncidentToCaseRule {

    Optional<CaseCategory> evaluate(IncidentPayload incident);

    /** Fields of {@code ehs.incident.received.v1} this kernel relies on. */
    record IncidentPayload(String incidentId, String externalRef, String employeeId, String employeeName,
                           String orgUnitCode, String incidentType, String severity, boolean injury) {
    }
}
