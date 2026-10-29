package com.wellnessz.e01.rules;

import java.time.ZoneOffset;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.model.CaseType;
import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OpenCaseCommand;
import com.wellnessz.casekernel.model.Urgency;

/** Builds the case for an incident – pre-filled with incident id, employee, unit, location, shift (E01-F01). */
@Component
public class IncidentCaseFactory {

    private final CaseService cases;

    public IncidentCaseFactory(CaseService cases) {
        this.cases = cases;
    }

    public OhCase open(IncidentFacts f, String employeeId, CaseType caseType, Urgency urgency) {
        var occurred = f.occurredAt();
        return cases.openIdempotent(OpenCaseCommand.builder(employeeId, RuleEngine.categoryOf(f, caseType), CaseSource.EHS_INCIDENT)
                .employeeName(f.employeeName())
                .fallbackOrgUnitCode(f.orgUnitCode())
                .caseType(caseType)
                .sourceRef(f.incidentId())
                .incidentRef(f.externalRef())
                .bodyPart(f.bodyPart())
                .occurredOn(occurred == null ? null : occurred.atZone(ZoneOffset.UTC).toLocalDate())
                .locationCode(f.location())
                .shift(f.shift())
                .urgency(urgency)
                .build());
    }
}
