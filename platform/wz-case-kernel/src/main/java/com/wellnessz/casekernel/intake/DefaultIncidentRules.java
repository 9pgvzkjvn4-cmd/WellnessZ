package com.wellnessz.casekernel.intake;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.CaseCategory;

/** Baseline rule: injuries, or incidents of severity MEDIUM and above involving an identified employee, open a case. */
@Component
public class DefaultIncidentRules implements IncidentToCaseRule {

    private static final Set<String> SIGNIFICANT = Set.of("MEDIUM", "HIGH", "CRITICAL");

    @Override
    public Optional<CaseCategory> evaluate(IncidentPayload i) {
        if (i.employeeId() == null || i.employeeId().isBlank()) {
            return Optional.empty();
        }
        String type = i.incidentType() == null ? "" : i.incidentType().toUpperCase(Locale.ROOT);
        if (type.contains("EXPOSURE")) {
            return Optional.of(CaseCategory.EXPOSURE);
        }
        if (i.injury()) {
            return Optional.of(CaseCategory.INJURY);
        }
        if (i.severity() != null && SIGNIFICANT.contains(i.severity().toUpperCase(Locale.ROOT))) {
            return Optional.of(CaseCategory.OTHER);
        }
        return Optional.empty();
    }
}
