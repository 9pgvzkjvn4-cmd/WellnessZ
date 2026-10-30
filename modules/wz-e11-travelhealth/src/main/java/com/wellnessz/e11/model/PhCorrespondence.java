package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Correspondence with the authority on a report (E11-F06); the summary is encrypted. */
@Entity(name = "E11PhCorrespondence")
@Table(name = "ph_correspondence", schema = "travelhealth")
public class PhCorrespondence {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "report_id")
    private UUID reportId;
    @Column(name = "direction")
    private String direction;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "summary", columnDefinition = "text")
    private String summary;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    /** New instance with a random id. */
    public static PhCorrespondence create() {
        PhCorrespondence e = new PhCorrespondence();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getReportId() {
        return reportId;
    }

    public void setReportId(UUID v) {
        this.reportId = v;
    }

    public String getDirection() {
        return direction;
    }

    public void setDirection(String v) {
        this.direction = v;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String v) {
        this.summary = v;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public void setDocumentId(UUID v) {
        this.documentId = v;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String v) {
        this.recordedBy = v;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant v) {
        this.recordedAt = v;
    }
}
