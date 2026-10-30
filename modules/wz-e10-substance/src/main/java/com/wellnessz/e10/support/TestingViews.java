package com.wellnessz.e10.support;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wellnessz.e10.model.SubstanceEnums.TestStatus;

/** API projections of the testing programme (contract §4). Results appear only in the MRO projections. */
public final class TestingViews {

    private TestingViews() {
    }

    public record PolicyView(String roleGroup, List<String> jobCodes, List<String> panels, BigDecimal randomDrugRatePct,
                             BigDecimal randomAlcoholRatePct, int alcoholWindowHours, int drugWindowHours, int leadTimeMinutes,
                             String jurisdiction, boolean consentRequired, String approvalStatus, String approvedBy, Instant approvedAt,
                             long version, Instant updatedAt) {
    }

    public record PoolMember(String employeeId, String displayName, String jobCode) {
    }

    public record PoolExclusionView(String employeeId, String displayName, String reason, LocalDate until) {
    }

    public record PoolView(String roleGroup, int size, List<PoolMember> members, List<PoolExclusionView> exclusions) {
    }

    public record SelectedEmployee(String employeeId, String displayName) {
    }

    public record SelectionView(UUID id, String ref, String roleGroup, String period, String testType, BigDecimal ratePct, int poolSize,
                                String poolHash, String seedCommitment, int selectedCount, List<SelectedEmployee> selected, Instant createdAt,
                                String createdBy, Instant notifiedAt, String policyApprovalStatus, long policyVersion) {
    }

    public record VerifyView(UUID selectionId, String ref, boolean reproducible, boolean poolHashMatches, boolean seedCommitmentMatches,
                             boolean selectionMatches, int poolSize, int selectedCount, String seed, String seedCommitment, String poolHash) {
    }

    public record CoverageRow(String roleGroup, int poolSize, int targetTests, int completed, BigDecimal coveragePct, boolean behindPlan) {
    }

    /** Work-list row for the programme manager, collectors and the MRO – never results; observations not for collectors. */
    public record Requirement(UUID id, String ref, String employeeId, String displayName, String reason, List<String> panels, String status,
                              Instant dueAt, BigDecimal hoursLeft, String siteCode, Instant dispatchedAt, Instant notifiedAt,
                              Instant alcoholDueAt, Instant drugDueAt, LocalDate scheduledFor, List<String> observations,
                              List<SpecimenSummary> specimens) {
    }

    public record SpecimenSummary(String specimenId, String specimenType, String kind, Instant collectedAt, boolean outOfWindow, UUID documentId) {
    }

    /** Manager / safety-manager view of a for-cause request: status only. */
    public record ForCauseView(UUID id, String ref, String employeeId, String displayName, String status, Instant observedAt, String location,
                               Instant dueAt, Instant createdAt) {
    }

    public record CustodyView(String specimenId, String event, Instant at, String by, String signer, String signerRole, Instant recordedAt) {
    }

    public record LabOrder(String specimenId, String orderType, String specimenType, List<String> panels, List<Analyte> analytes,
                           Instant collectedAt, String siteCode, boolean splitSpecimen, List<CustodyView> custody) {
    }

    public record Analyte(String analyte, String cutoff, String unit) {
    }

    public record LabResult(String analyte, String cutoff, String value, String unit, String outcome) {
    }

    public record LabAck(String specimenId, String labRef, String status) {
    }

    public record MroQueueItem(UUID testId, String ref, String employeeId, String displayName, String reason, String specimenId, String validity,
                               List<LabResult> results, Instant receivedAt, String breathResult, String refusalReason,
                               String refusalObservations, List<LabResult> splitResults, Instant splitRequestedAt, List<String> panels) {
    }

    public record MroDecisionView(UUID testId, String ref, String status, String verdict, Instant decidedAt, UUID caseId, UUID rtdPlanId,
                                  boolean splitSpecimenRequested) {
    }

    public record MyNotice(String ref, String status, Instant reportBy, String siteCode) {
    }

    public record PlanTest(UUID id, String ref, String reason, String status, LocalDate scheduledFor) {
    }

    public record RtdPlanView(UUID id, String ref, String employeeId, String displayName, UUID caseId, String status, String evaluator,
                              String treatmentProvider, int followUpTests, int followUpMonths, LocalDate startDate, LocalDate endDate,
                              String evaluationOutcome, Instant evaluatedAt, String evaluatedBy, PlanTest rtdTest, List<PlanTest> followUps,
                              String dutyStatus, Instant createdAt) {
    }

    public record DutyStatusView(String employeeId, String displayName, String dutyStatus, Instant since) {
    }

    /**
     * Status visible outside the MRO (employee, requesting manager): the test progress without the outcome – collected /
     * in laboratory / under review all read COLLECTED, every verified outcome reads COMPLETED, closed tests CLOSED.
     */
    public static String publicStatus(String status) {
        TestStatus s = TestStatus.valueOf(status);
        return switch (s) {
            case REQUIRED, NOTIFIED, DISPATCHED -> s.name();
            case COLLECTED, IN_LAB, MRO_REVIEW -> "COLLECTED";
            case VERIFIED_NEGATIVE, VERIFIED_POSITIVE, REFUSAL -> "COMPLETED";
            case CANCELLED, WINDOW_MISSED -> "CLOSED";
        };
    }
}
