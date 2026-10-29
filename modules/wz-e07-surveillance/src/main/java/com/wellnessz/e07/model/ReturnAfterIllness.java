package com.wellnessz.e07.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Return-after-serious-illness medical requirement (E07-BL-020). */
@Entity
@Table(name = "return_after_illness", schema = "surveillance")
public class ReturnAfterIllness {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "reason")
    private String reason;
    @Column(name = "source")
    private String source;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "status")
    private String status;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "completed_by")
    private String completedBy;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;

    public ReturnAfterIllness() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID v) { this.encounterId = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
