package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Photo of an assessed workstation / task, stored as a non-clinical document. */
@Entity(name = "E12ErgonomicPhoto")
@Table(name = "ergonomic_photo", schema = "promotion")
public class ErgonomicPhoto {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "assessment_id")
    private UUID assessmentId;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "uploaded_by")
    private String uploadedBy;
    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    public ErgonomicPhoto() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getAssessmentId() { return assessmentId; }
    public void setAssessmentId(UUID v) { this.assessmentId = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant v) { this.uploadedAt = v; }
}
