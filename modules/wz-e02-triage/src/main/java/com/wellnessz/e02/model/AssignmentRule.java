package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Assignment rule (E02-F03): conditions are wildcards when null; lowest priority wins. Strategy NONE, ROUND_ROBIN, LEAST_LOADED, SPECIFIC_USER. */
@Entity
@Table(name = "assignment_rule", schema = "triage")
public class AssignmentRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false)
    private int priority;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "subsidiary_code", length = 20)
    private String subsidiaryCode;
    @Column(name = "site_code", length = 40)
    private String siteCode;
    @Column(length = 8)
    private String language;
    @Column(name = "case_type", length = 40)
    private String caseType;
    @Column(name = "safety_category", length = 40)
    private String safetyCategory;
    @Column(length = 60)
    private String specialty;
    @Column(name = "target_queue", nullable = false, length = 40)
    private String targetQueue;
    @Column(nullable = false, length = 20)
    private String strategy;
    @Column(name = "target_username", length = 200)
    private String targetUsername;
    @Version
    private Long version;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AssignmentRule() {
        // JPA; fields are populated through setters by the owning service
    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getSubsidiaryCode() {
        return subsidiaryCode;
    }

    public void setSubsidiaryCode(String subsidiaryCode) {
        this.subsidiaryCode = subsidiaryCode;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCaseType() {
        return caseType;
    }

    public void setCaseType(String caseType) {
        this.caseType = caseType;
    }

    public String getSafetyCategory() {
        return safetyCategory;
    }

    public void setSafetyCategory(String safetyCategory) {
        this.safetyCategory = safetyCategory;
    }

    public String getSpecialty() {
        return specialty;
    }

    public void setSpecialty(String specialty) {
        this.specialty = specialty;
    }

    public String getTargetQueue() {
        return targetQueue;
    }

    public void setTargetQueue(String targetQueue) {
        this.targetQueue = targetQueue;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public void setTargetUsername(String targetUsername) {
        this.targetUsername = targetUsername;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
