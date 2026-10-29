package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Complexity / risk score history (drivers as JSON). */
@Entity
@Table(name = "complexity_assessment", schema = "triage")
public class ComplexityAssessment {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(nullable = false)
    private int score;
    @Column(nullable = false, columnDefinition = "text")
    private String drivers;
    @Column(nullable = false, length = 20)
    private String intensity;
    @Column(name = "expected_duration_days")
    private Integer expectedDurationDays;
    @Column(name = "actual_absence_days")
    private Integer actualAbsenceDays;
    @Column(name = "calculated_at", nullable = false)
    private Instant calculatedAt;

    public ComplexityAssessment() {
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

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getDrivers() {
        return drivers;
    }

    public void setDrivers(String drivers) {
        this.drivers = drivers;
    }

    public String getIntensity() {
        return intensity;
    }

    public void setIntensity(String intensity) {
        this.intensity = intensity;
    }

    public Integer getExpectedDurationDays() {
        return expectedDurationDays;
    }

    public void setExpectedDurationDays(Integer expectedDurationDays) {
        this.expectedDurationDays = expectedDurationDays;
    }

    public Integer getActualAbsenceDays() {
        return actualAbsenceDays;
    }

    public void setActualAbsenceDays(Integer actualAbsenceDays) {
        this.actualAbsenceDays = actualAbsenceDays;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(Instant calculatedAt) {
        this.calculatedAt = calculatedAt;
    }
}
