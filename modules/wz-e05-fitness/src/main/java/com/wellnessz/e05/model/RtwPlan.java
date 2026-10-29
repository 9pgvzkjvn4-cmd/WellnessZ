package com.wellnessz.e05.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Phased return-to-work plan (E05-BL-007). */
@Entity
@Table(name = "rtw_plan", schema = "fitness")
public class RtwPlan {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "status")
    private String status;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "goal")
    private String goal;
    @Column(name = "phases", columnDefinition = "text")
    private String phases;
    @Column(name = "review_dates")
    private String reviewDates;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "activated_at")
    private Instant activatedAt;
    @Column(name = "employee_ack_at")
    private Instant employeeAckAt;
    @Column(name = "employee_agree")
    private Boolean employeeAgree;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "employee_comment", columnDefinition = "text")
    private String employeeComment;
    @Column(name = "manager_ack_at")
    private Instant managerAckAt;
    @Column(name = "manager_ack_by")
    private String managerAckBy;
    @Column(name = "manager_comment")
    private String managerComment;
    @Column(name = "document_id")
    private UUID documentId;

    public RtwPlan() {
        // JPA; populated through setters by the owning service
    }

    public static RtwPlan create() {
        RtwPlan x = new RtwPlan();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public String getGoal() { return goal; }
    public void setGoal(String v) { this.goal = v; }
    public String getPhases() { return phases; }
    public void setPhases(String v) { this.phases = v; }
    public String getReviewDates() { return reviewDates; }
    public void setReviewDates(String v) { this.reviewDates = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getActivatedAt() { return activatedAt; }
    public void setActivatedAt(Instant v) { this.activatedAt = v; }
    public Instant getEmployeeAckAt() { return employeeAckAt; }
    public void setEmployeeAckAt(Instant v) { this.employeeAckAt = v; }
    public Boolean getEmployeeAgree() { return employeeAgree; }
    public void setEmployeeAgree(Boolean v) { this.employeeAgree = v; }
    public String getEmployeeComment() { return employeeComment; }
    public void setEmployeeComment(String v) { this.employeeComment = v; }
    public Instant getManagerAckAt() { return managerAckAt; }
    public void setManagerAckAt(Instant v) { this.managerAckAt = v; }
    public String getManagerAckBy() { return managerAckBy; }
    public void setManagerAckBy(String v) { this.managerAckBy = v; }
    public String getManagerComment() { return managerComment; }
    public void setManagerComment(String v) { this.managerComment = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
}
