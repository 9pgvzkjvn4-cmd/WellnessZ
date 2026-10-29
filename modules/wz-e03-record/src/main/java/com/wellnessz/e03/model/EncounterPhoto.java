package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Clinical photograph linked to an encounter (stored by the document service). */
@Entity
@Table(name = "encounter_photo", schema = "ohrecord")
public class EncounterPhoto {

    @Id
    private UUID id;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "body_region")
    private String bodyRegion;
    @Column(name = "taken_at")
    private Instant takenAt;
    @Column(name = "taken_by")
    private String takenBy;
    @Column(name = "consent_recorded")
    private boolean consentRecorded;

    public EncounterPhoto() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(UUID encounterId) {
        this.encounterId = encounterId;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID documentId) {
        this.documentId = documentId;
    }

    public String getBodyRegion() {
        return bodyRegion;
    }

    public void setBodyRegion(String bodyRegion) {
        this.bodyRegion = bodyRegion;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public void setTakenAt(Instant takenAt) {
        this.takenAt = takenAt;
    }

    public String getTakenBy() {
        return takenBy;
    }

    public void setTakenBy(String takenBy) {
        this.takenBy = takenBy;
    }

    public boolean isConsentRecorded() {
        return consentRecorded;
    }

    public void setConsentRecorded(boolean consentRecorded) {
        this.consentRecorded = consentRecorded;
    }
}
