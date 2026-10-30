package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Screening result – clinical (OH only); measures and flags encrypted at field level. */
@Entity(name = "E12ScreeningResult")
@Table(name = "screening_result", schema = "promotion")
public class ScreeningResult {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "participant_id")
    private UUID participantId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "measures", columnDefinition = "text")
    private String measures;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "flags", columnDefinition = "text")
    private String flags;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "follow_up_due_at")
    private Instant followUpDueAt;
    @Column(name = "follow_up_opened_at")
    private Instant followUpOpenedAt;
    @Column(name = "letter_document_id")
    private UUID letterDocumentId;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public ScreeningResult() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID v) { this.eventId = v; }
    public UUID getParticipantId() { return participantId; }
    public void setParticipantId(UUID v) { this.participantId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getMeasures() { return measures; }
    public void setMeasures(String v) { this.measures = v; }
    public String getFlags() { return flags; }
    public void setFlags(String v) { this.flags = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public Instant getFollowUpDueAt() { return followUpDueAt; }
    public void setFollowUpDueAt(Instant v) { this.followUpDueAt = v; }
    public Instant getFollowUpOpenedAt() { return followUpOpenedAt; }
    public void setFollowUpOpenedAt(Instant v) { this.followUpOpenedAt = v; }
    public UUID getLetterDocumentId() { return letterDocumentId; }
    public void setLetterDocumentId(UUID v) { this.letterDocumentId = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
