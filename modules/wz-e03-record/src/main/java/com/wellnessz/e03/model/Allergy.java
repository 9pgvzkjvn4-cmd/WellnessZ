package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Allergy / intolerance (FHIR AllergyIntolerance). */
@Entity
@Table(name = "allergy", schema = "ohrecord")
public class Allergy {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "substance_system")
    private String substanceSystem;
    @Column(name = "substance_code")
    private String substanceCode;
    @Column(name = "substance_display")
    private String substanceDisplay;
    @Column(name = "category")
    private String category;
    @Column(name = "criticality")
    private String criticality;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reaction", columnDefinition = "text")
    private String reaction;
    @Column(name = "status")
    private String status;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Allergy() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getSubstanceSystem() {
        return substanceSystem;
    }

    public void setSubstanceSystem(String substanceSystem) {
        this.substanceSystem = substanceSystem;
    }

    public String getSubstanceCode() {
        return substanceCode;
    }

    public void setSubstanceCode(String substanceCode) {
        this.substanceCode = substanceCode;
    }

    public String getSubstanceDisplay() {
        return substanceDisplay;
    }

    public void setSubstanceDisplay(String substanceDisplay) {
        this.substanceDisplay = substanceDisplay;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCriticality() {
        return criticality;
    }

    public void setCriticality(String criticality) {
        this.criticality = criticality;
    }

    public String getReaction() {
        return reaction;
    }

    public void setReaction(String reaction) {
        this.reaction = reaction;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
