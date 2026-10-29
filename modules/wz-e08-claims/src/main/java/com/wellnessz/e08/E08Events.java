package com.wellnessz.e08;

import com.wellnessz.common.events.DomainEvent;

/**
 * Events emitted by E08 (contract §5) – identifiers and status only. External destinations ({@code insurer-gateway},
 * {@code authority-gateway}, {@code erp-finance}) are flat and allow-listed: references, never documents or clinical text.
 */
public final class E08Events {

    public static final String INSURER_GATEWAY = "insurer-gateway";
    public static final String AUTHORITY_GATEWAY = "authority-gateway";
    public static final String ERP_FINANCE = "erp-finance";

    private E08Events() {
    }

    /** In-process: claim status changed. */
    public record ClaimStatusChanged(String claimId, String caseId, String status) implements DomainEvent {
        @Override
        public String eventType() {
            return "claim.status.v1";
        }

        @Override
        public String aggregateType() {
            return "CLAIM";
        }

        @Override
        public String aggregateId() {
            return claimId;
        }
    }

    /** In-process: a nurse case manager was assigned to a lost-time claim. */
    public record NurseAssigned(String claimId, String username) implements DomainEvent {
        @Override
        public String eventType() {
            return "claim.nurse.assigned.v1";
        }

        @Override
        public String aggregateType() {
            return "CLAIM";
        }

        @Override
        public String aggregateId() {
            return claimId;
        }
    }

    /** First report submitted to an insurer / TPA ({@code insurer-gateway}, allow-listed fields only). */
    public record InsurerReportSubmitted(String messageId, String claimRef, String reportRef, String formCode, String payerCode, String employeeId,
                                         String incidentDate, String submittedAt, String deadlineAt) implements DomainEvent {
        @Override
        public String eventType() {
            return "claim.report.submitted.v1";
        }

        @Override
        public String aggregateType() {
            return "FIRST_REPORT";
        }

        @Override
        public String aggregateId() {
            return reportRef;
        }
    }

    /** Statutory first report submitted to an authority ({@code authority-gateway}): no employee identifiers. */
    public record AuthorityReportSubmitted(String messageId, String reportRef, String formCode, String jurisdiction, String employerRef,
                                           String incidentDate, String submittedAt, String deadlineAt) implements DomainEvent {
        @Override
        public String eventType() {
            return "claim.report.submitted.v1";
        }

        @Override
        public String aggregateType() {
            return "FIRST_REPORT";
        }

        @Override
        public String aggregateId() {
            return reportRef;
        }
    }

    /** Monthly cost export batch ({@code erp-finance}): totals, counts and checksum – the CSV is fetched separately. */
    public record CostExportGenerated(String messageId, String batchId, String month, String amount, String currency, int entryCount,
                                      String checksum) implements DomainEvent {
        @Override
        public String eventType() {
            return "claim.cost.export.v1";
        }

        @Override
        public String aggregateType() {
            return "COST_EXPORT";
        }

        @Override
        public String aggregateId() {
            return batchId;
        }
    }
}
