package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Public-health / authority report (E11-F06) with its statutory deadline. */
@Entity(name = "E11PhReport")
@Table(name = "ph_report", schema = "travelhealth")
public class PhReport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "report_ref")
    private String reportRef;
    @Column(name = "cd_case_id")
    private UUID cdCaseId;
    @Column(name = "exposure_id")
    private UUID exposureId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "authority_code")
    private String authorityCode;
    @Column(name = "subject")
    private String subject;
    @Column(name = "status")
    private String status;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "channel")
    private String channel;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "submission_reference")
    private String submissionReference;
    @Column(name = "submitted_by")
    private String submittedBy;
    @Column(name = "due_soon_alerted_at")
    private Instant dueSoonAlertedAt;
    @Column(name = "overdue_alerted_at")
    private Instant overdueAlertedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** New instance with a random id. */
    public static PhReport create() {
        PhReport e = new PhReport();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getReportRef() {
        return reportRef;
    }

    public void setReportRef(String v) {
        this.reportRef = v;
    }

    public UUID getCdCaseId() {
        return cdCaseId;
    }

    public void setCdCaseId(UUID v) {
        this.cdCaseId = v;
    }

    public UUID getExposureId() {
        return exposureId;
    }

    public void setExposureId(UUID v) {
        this.exposureId = v;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID v) {
        this.caseId = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getAuthorityCode() {
        return authorityCode;
    }

    public void setAuthorityCode(String v) {
        this.authorityCode = v;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String v) {
        this.subject = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        this.status = v;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant v) {
        this.dueAt = v;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID v) {
        this.documentId = v;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String v) {
        this.channel = v;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant v) {
        this.submittedAt = v;
    }

    public String getSubmissionReference() {
        return submissionReference;
    }

    public void setSubmissionReference(String v) {
        this.submissionReference = v;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String v) {
        this.submittedBy = v;
    }

    public Instant getDueSoonAlertedAt() {
        return dueSoonAlertedAt;
    }

    public void setDueSoonAlertedAt(Instant v) {
        this.dueSoonAlertedAt = v;
    }

    public Instant getOverdueAlertedAt() {
        return overdueAlertedAt;
    }

    public void setOverdueAlertedAt(Instant v) {
        this.overdueAlertedAt = v;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String v) {
        this.createdBy = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }
}
