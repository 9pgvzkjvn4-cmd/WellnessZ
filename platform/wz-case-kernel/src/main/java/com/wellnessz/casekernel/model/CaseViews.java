package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.wellnessz.common.model.DisclosureLevel;

/** Projections chosen by the ABAC disclosure level. The status-only view structurally cannot carry clinical data. */
public final class CaseViews {

    private CaseViews() {
    }

    /** What operations, line managers and HR are allowed to see. */
    public record StatusOnly(UUID id, String caseRef, String employeeDisplayName, CaseStatus status, FitnessStatus fitnessStatus,
                             LocalDate restrictionUntil, Instant updatedAt, DisclosureLevel disclosure) {
        public static StatusOnly of(OhCase c) {
            return new StatusOnly(c.getId(), c.getCaseRef(), c.getEmployeeDisplayName(), c.getStatus(), c.getFitnessStatus(),
                    c.getRestrictionUntil(), c.getUpdatedAt(), DisclosureLevel.STATUS_ONLY);
        }
    }

    /** OH clinical view, and the data subject's own view. */
    public record Clinical(UUID id, String caseRef, String employeeId, String employeeDisplayName, String orgUnitCode,
                           CaseCategory category, CaseSource source, CaseStatus status, FitnessStatus fitnessStatus,
                           LocalDate restrictionUntil, String clinicalSummary, Instant createdAt, Instant updatedAt,
                           DisclosureLevel disclosure) {
        public static Clinical of(OhCase c, DisclosureLevel level) {
            return new Clinical(c.getId(), c.getCaseRef(), c.getEmployeeId(), c.getEmployeeDisplayName(), c.getOrgUnitCode(),
                    c.getCategory(), c.getSource(), c.getStatus(), c.getFitnessStatus(), c.getRestrictionUntil(),
                    c.getClinicalSummary(), c.getCreatedAt(), c.getUpdatedAt(), level);
        }
    }
}
