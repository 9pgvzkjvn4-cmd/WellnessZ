package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Assignment / re-assignment history (full history retained, E02-HLR-03). */
@Entity
@Table(name = "case_assignment", schema = "triage")
public class CaseAssignment {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "from_username", length = 200)
    private String fromUsername;
    @Column(name = "to_username", length = 200)
    private String toUsername;
    @Column(name = "queue_code", length = 40)
    private String queueCode;
    @Column(length = 500)
    private String reason;
    @Column(nullable = false, length = 200)
    private String actor;
    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    public CaseAssignment() {
        // JPA; fields are populated through setters by the owning service
    }


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public String getFromUsername() {
        return fromUsername;
    }

    public void setFromUsername(String fromUsername) {
        this.fromUsername = fromUsername;
    }

    public String getToUsername() {
        return toUsername;
    }

    public void setToUsername(String toUsername) {
        this.toUsername = toUsername;
    }

    public String getQueueCode() {
        return queueCode;
    }

    public void setQueueCode(String queueCode) {
        this.queueCode = queueCode;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }
}
