package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.wellnessz.secrets.EncryptedStringConverter;

/** Structured shift handover with acknowledgement (E02-BL-019). */
@Entity
@Table(name = "handover", schema = "triage")
public class Handover {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "from_username", nullable = false, length = 200)
    private String fromUsername;
    @Column(name = "to_username", nullable = false, length = 200)
    private String toUsername;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String summary;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "outstanding_actions", columnDefinition = "text")
    private String outstandingActions;
    @Column(name = "next_contact_at")
    private Instant nextContactAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    public Handover() {
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getOutstandingActions() {
        return outstandingActions;
    }

    public void setOutstandingActions(String outstandingActions) {
        this.outstandingActions = outstandingActions;
    }

    public Instant getNextContactAt() {
        return nextContactAt;
    }

    public void setNextContactAt(Instant nextContactAt) {
        this.nextContactAt = nextContactAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }
}
