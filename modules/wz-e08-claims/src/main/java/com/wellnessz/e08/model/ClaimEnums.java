package com.wellnessz.e08.model;

/** Enumerations of the claims domain (contract §4). Stored as their names. */
public final class ClaimEnums {

    public enum ClaimType { WORKERS_COMP, EMPLOYER_LIABILITY, GROUP_MEDICAL, THIRD_PARTY }

    public enum ClaimStatus { NOTIFIED, OPEN, UNDER_REVIEW, SETTLED, CLOSED, LITIGATED }

    public enum CostCategory { MEDICAL, REHAB, INDEMNITY, ADMIN, SETTLEMENT, LEGAL }

    public enum CostKind { ESTIMATE, INVOICE, PAYMENT, RESERVE }

    public enum ReportStatus { DRAFT, SUBMITTED, ACKNOWLEDGED, REJECTED, LATE }

    public enum SubmissionChannel { API, EMAIL, PORTAL }

    public enum DisclosureSetStatus { DRAFT, SENT, REVOKED }

    public enum LegalRequestType { MEDICO_LEGAL_REPORT, EXPERT_OPINION, IME }

    public enum LegalRequestStatus { OPEN, IN_PROGRESS, REPORT_READY, CLOSED }

    /** Side of the collaboration a shared task is assigned to. */
    public enum AssigneeRole { CLAIMS, NURSE_CM, ADJUSTER }

    public enum TaskStatus { OPEN, DONE }

    public enum ExportStatus { GENERATED, RECONCILED, MISMATCH }

    private ClaimEnums() {
    }
}
