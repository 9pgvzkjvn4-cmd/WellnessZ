package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Case conference scheduled on a long-term absence pathway. */
@Entity
@Table(name = "case_conference", schema = "absence")
public class LtaConference {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "scheduled_for")
    private Instant scheduledFor;
    @Column(name = "participants")
    private String participants;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public LtaConference() {
        // JPA; populated through setters by the owning service
    }

    public static LtaConference create() {
        LtaConference x = new LtaConference();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public Instant getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(Instant v) { this.scheduledFor = v; }
    public String getParticipants() { return participants; }
    public void setParticipants(String v) { this.participants = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
