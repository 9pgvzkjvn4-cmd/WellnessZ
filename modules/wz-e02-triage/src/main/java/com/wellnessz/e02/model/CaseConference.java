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

/** Case conference with role-limited minutes (E02-BL-012). */
@Entity
@Table(name = "case_conference", schema = "triage")
public class CaseConference {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;
    @Column(nullable = false, length = 2000)
    private String attendees;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String agenda;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String minutes;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String decisions;
    @Column(nullable = false, length = 20)
    private String visibility;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public CaseConference() {
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

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public String getAttendees() {
        return attendees;
    }

    public void setAttendees(String attendees) {
        this.attendees = attendees;
    }

    public String getAgenda() {
        return agenda;
    }

    public void setAgenda(String agenda) {
        this.agenda = agenda;
    }

    public String getMinutes() {
        return minutes;
    }

    public void setMinutes(String minutes) {
        this.minutes = minutes;
    }

    public String getDecisions() {
        return decisions;
    }

    public void setDecisions(String decisions) {
        this.decisions = decisions;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
