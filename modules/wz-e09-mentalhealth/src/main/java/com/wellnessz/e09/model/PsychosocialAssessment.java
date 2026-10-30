package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Department-level psychosocial risk assessment (ISO 45003), insert-only – a review creates a new assessment. */
@Entity(name = "E09PsychosocialAssessment")
@Table(name = "psychosocial_assessment", schema = "mentalhealth")
public class PsychosocialAssessment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "assessed_on")
    private LocalDate assessedOn;
    @Column(name = "review_date")
    private LocalDate reviewDate;
    @Column(name = "factors")
    private String factors;
    @Column(name = "overall_score")
    private int overallScore;
    @Column(name = "rag")
    private String rag;
    @Column(name = "assessed_by")
    private String assessedBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public PsychosocialAssessment() {
        // JPA; populated through setters by the owning service
    }

    public static PsychosocialAssessment create() {
        PsychosocialAssessment x = new PsychosocialAssessment();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public LocalDate getAssessedOn() { return assessedOn; }
    public void setAssessedOn(LocalDate v) { this.assessedOn = v; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate v) { this.reviewDate = v; }
    public String getFactors() { return factors; }
    public void setFactors(String v) { this.factors = v; }
    public int getOverallScore() { return overallScore; }
    public void setOverallScore(int v) { this.overallScore = v; }
    public String getRag() { return rag; }
    public void setRag(String v) { this.rag = v; }
    public String getAssessedBy() { return assessedBy; }
    public void setAssessedBy(String v) { this.assessedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
