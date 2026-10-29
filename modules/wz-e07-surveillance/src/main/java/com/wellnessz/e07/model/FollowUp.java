package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Follow-up raised from an abnormal surveillance result. */
@Entity
@Table(name = "follow_up", schema = "surveillance")
public class FollowUp {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "programme_code")
    private String programmeCode;
    @Column(name = "rule_code")
    private String ruleCode;
    @Column(name = "result_id")
    private UUID resultId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "recheck_due_on")
    private LocalDate recheckDueOn;
    @Column(name = "regulatory_notification_required")
    private boolean regulatoryNotificationRequired;
    @Column(name = "status")
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "reviewed_by")
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Column(name = "review_decision")
    private String reviewDecision;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    public FollowUp() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getProgrammeCode() { return programmeCode; }
    public void setProgrammeCode(String v) { this.programmeCode = v; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String v) { this.ruleCode = v; }
    public UUID getResultId() { return resultId; }
    public void setResultId(UUID v) { this.resultId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public LocalDate getRecheckDueOn() { return recheckDueOn; }
    public void setRecheckDueOn(LocalDate v) { this.recheckDueOn = v; }
    public boolean isRegulatoryNotificationRequired() { return regulatoryNotificationRequired; }
    public void setRegulatoryNotificationRequired(boolean v) { this.regulatoryNotificationRequired = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public String getReviewDecision() { return reviewDecision; }
    public void setReviewDecision(String v) { this.reviewDecision = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
}
