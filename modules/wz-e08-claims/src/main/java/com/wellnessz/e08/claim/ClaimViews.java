package com.wellnessz.e08.claim;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.wellnessz.e08.support.Money;

/** JSON views of the claim record (contract §4.1). Money fields are present only for roles that may see money. */
public final class ClaimViews {

    private ClaimViews() {
    }

    public record Adjuster(String name, String email, String username) {
    }

    public record Subrogation(String thirdPartyName, String thirdPartyType, String thirdPartyInsurer, String reference, String description,
                              Instant flaggedAt, String flaggedBy) {
    }

    public record ClaimView(UUID id, String claimRef, UUID caseId, String caseRef, String employeeId, String displayName, String unit,
                            String claimType, String status, String payerCode, String payerName, String policyNumber, Adjuster adjuster,
                            boolean lostTime, LocalDate incidentDate, Instant notifiedAt, Instant openedAt, Instant settledAt, Instant closedAt,
                            String nurseCaseManager, String nurseCaseManagerName, Instant nurseAssignedAt, Map<String, Money> reserves,
                            Money paidTotal, Subrogation subrogation, boolean litigationHold, boolean privileged, long openTasks,
                            String reportStatus, String notes) {
    }

    public record HistoryEntry(String type, String fromStatus, String status, String reason, String by, Instant at) {
    }

    public record ReportSummary(UUID id, String ref, String formCode, String recipientCode, String status, Instant dueAt, Instant submittedAt,
                                Instant acknowledgedAt) {
    }

    public record DisclosureSetSummary(UUID id, String recipientCode, String status, int documents, Instant sentAt) {
    }

    public record LegalRequestSummary(UUID id, String ref, String type, String status, LocalDate dueDate) {
    }

    public record ClaimDetail(UUID id, String claimRef, UUID caseId, String caseRef, String employeeId, String displayName, String unit,
                              String claimType, String status, String payerCode, String payerName, String policyNumber, Adjuster adjuster,
                              boolean lostTime, LocalDate incidentDate, Instant notifiedAt, Instant openedAt, Instant settledAt, Instant closedAt,
                              String nurseCaseManager, String nurseCaseManagerName, Instant nurseAssignedAt, Map<String, Money> reserves,
                              Money paidTotal, Subrogation subrogation, boolean litigationHold, String holdReason, boolean privileged,
                              String privilegeBasis, long openTasks, String reportStatus, String notes, List<String> nextStatuses,
                              List<HistoryEntry> history, List<ReportSummary> reports, List<DisclosureSetSummary> disclosureSets,
                              List<LegalRequestSummary> legalRequests) {

        static ClaimDetail of(ClaimView v, String holdReason, String privilegeBasis, List<String> nextStatuses, List<HistoryEntry> history,
                              List<ReportSummary> reports, List<DisclosureSetSummary> sets, List<LegalRequestSummary> legal) {
            return new ClaimDetail(v.id(), v.claimRef(), v.caseId(), v.caseRef(), v.employeeId(), v.displayName(), v.unit(), v.claimType(),
                    v.status(), v.payerCode(), v.payerName(), v.policyNumber(), v.adjuster(), v.lostTime(), v.incidentDate(), v.notifiedAt(),
                    v.openedAt(), v.settledAt(), v.closedAt(), v.nurseCaseManager(), v.nurseCaseManagerName(), v.nurseAssignedAt(), v.reserves(),
                    v.paidTotal(), v.subrogation(), v.litigationHold(), holdReason, v.privileged(), privilegeBasis, v.openTasks(),
                    v.reportStatus(), v.notes(), nextStatuses, history, reports, sets, legal);
        }
    }

    /** Case header badge (GET /cases/{caseId}/claim-status). */
    public record CaseClaimStatus(UUID claimId, String claimRef, String claimType, String status, boolean lostTime, boolean litigationHold,
                                  Money paidTotal) {
    }

    /** HR workspace: status only. */
    public record WorkspaceClaim(String claimRef, String employeeId, String displayName, String claimType, String status, boolean lostTime,
                                 LocalDate incidentDate) {
    }
}
