package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wellnessz.common.model.DisclosureLevel;

/** Projections chosen by the ABAC disclosure level. The status-only view structurally cannot carry clinical data. */
public final class CaseViews {

    private CaseViews() {
    }

    /** What operations, line managers, HR, HSE, crew scheduling and hygiene managers are allowed to see. */
    public record StatusOnly(UUID id, String caseRef, String employeeDisplayName, CaseStatus status, FitnessStatus fitnessStatus,
                             LocalDate restrictionUntil, Instant updatedAt, DisclosureLevel disclosure) {
        public static StatusOnly of(OhCase c) {
            return new StatusOnly(c.getId(), c.getCaseRef(), c.getEmployeeDisplayName(), c.getStatus(), c.getFitnessStatus(),
                    c.getRestrictionUntil(), c.getUpdatedAt(), DisclosureLevel.STATUS_ONLY);
        }
    }

    public record LinkView(String type, String ref, Instant createdAt) {
    }

    /** OH clinical view, and the data subject's own view. */
    public record Clinical(UUID id, String caseRef, String employeeId, String employeeDisplayName, String employeeName,
                           String subsidiaryCode, String departmentCode, String orgUnitCode, String costCentre, String jobCode,
                           String locationCode, String shift, boolean hrisOverride, CaseCategory category, CaseType caseType,
                           CaseSource source, String incidentRef, String bodyPart, LocalDate occurredOn, String language,
                           String templateCode, CaseStatus status, Urgency urgency, boolean safetyCritical,
                           SafetyCategory safetyCategory, String ownerUsername, String queueCode,
                           CaseEnums.WaitingState waitingState, CaseEnums.Intensity intensity, Integer complexityScore,
                           boolean earlyIntervention, boolean duplicateSuspect, boolean escalated, UUID mergedIntoCaseId,
                           CaseEnums.Recordability recordability, CaseEnums.WorkRelated workRelated,
                           FitnessStatus fitnessStatus, LocalDate restrictionUntil, String clinicalSummary,
                           String intakeDetails, List<LinkView> links, List<AttachmentMeta> attachments,
                           Instant createdAt, Instant updatedAt, DisclosureLevel disclosure, CodedConcept primaryDiagnosis) {

        public static Clinical of(OhCase c, DisclosureLevel level) {
            return of(c, level, List.of(), List.of());
        }

        public static Clinical of(OhCase c, DisclosureLevel level, List<LinkView> links, List<AttachmentMeta> attachments) {
            return new Clinical(c.getId(), c.getCaseRef(), c.getEmployeeId(), c.getEmployeeDisplayName(), c.getEmployeeDisplayName(),
                    c.getSubsidiaryCode(), c.getOrgUnitCode(), c.getOrgUnitCode(), c.getCostCentre(), c.getJobCode(),
                    c.getLocationCode(), c.getShift(), c.isHrisOverride(), c.getCategory(), c.getCaseType(), c.getSource(),
                    c.getIncidentRef(), c.getBodyPart(), c.getOccurredOn(), c.getLanguage(), c.getTemplateCode(), c.getStatus(),
                    c.getUrgency(), c.isSafetyCritical(), c.getSafetyCategory(), c.getOwnerUsername(), c.getQueueCode(),
                    c.getWaitingState(), c.getIntensity(), c.getComplexityScore(), c.isEarlyIntervention(),
                    c.isDuplicateSuspect(), c.isEscalated(), c.getMergedIntoCaseId(), c.getRecordability(), c.getWorkRelated(),
                    c.getFitnessStatus(), c.getRestrictionUntil(), c.getClinicalSummary(), c.getIntakeDetails(), links,
                    attachments, c.getCreatedAt(), c.getUpdatedAt(), level,
                    c.hasCodedDiagnosis() ? new CodedConcept(c.getPrimaryDiagnosisSystem(), c.getPrimaryDiagnosisCode(),
                            c.getPrimaryDiagnosisDisplay()) : null);
        }
    }

    /** A coded clinical concept (SNOMED CT / ICD / LOINC). */
    public record CodedConcept(String system, String code, String display) {
    }

    /** Self view for the data subject: own case without internal routing data. */
    public record Self(UUID id, String caseRef, CaseCategory category, CaseStatus status, FitnessStatus fitnessStatus,
                       LocalDate restrictionUntil, Instant createdAt, Instant updatedAt, DisclosureLevel disclosure) {
        public static Self of(OhCase c) {
            return new Self(c.getId(), c.getCaseRef(), c.getCategory(), c.getStatus(), c.getFitnessStatus(),
                    c.getRestrictionUntil(), c.getCreatedAt(), c.getUpdatedAt(), DisclosureLevel.SELF);
        }
    }
}
