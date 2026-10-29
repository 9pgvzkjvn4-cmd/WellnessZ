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

/** Triage quality audit sample (E02-BL-016). */
@Entity
@Table(name = "triage_audit_sample", schema = "triage")
public class AuditSample {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;
    @Column(name = "sampled_at", nullable = false)
    private Instant sampledAt;
    @Column(length = 200)
    private String reviewer;
    @Column
    private Boolean agree;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String comment;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public AuditSample() {
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

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public void setAssessmentId(UUID assessmentId) {
        this.assessmentId = assessmentId;
    }

    public Instant getSampledAt() {
        return sampledAt;
    }

    public void setSampledAt(Instant sampledAt) {
        this.sampledAt = sampledAt;
    }

    public String getReviewer() {
        return reviewer;
    }

    public void setReviewer(String reviewer) {
        this.reviewer = reviewer;
    }

    public Boolean getAgree() {
        return agree;
    }

    public void setAgree(Boolean agree) {
        this.agree = agree;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Instant reviewedAt) {
        this.reviewedAt = reviewedAt;
    }
}
