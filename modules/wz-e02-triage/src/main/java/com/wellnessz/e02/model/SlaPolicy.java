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

/** SLA target per milestone, urgency and (optionally) case type (E01-BL-023). */
@Entity
@Table(name = "sla_policy", schema = "triage")
public class SlaPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "case_type", length = 40)
    private String caseType;
    @Column(nullable = false, length = 20)
    private String urgency;
    @Column(nullable = false, length = 30)
    private String milestone;
    @Column(name = "target_minutes", nullable = false)
    private int targetMinutes;
    @Column(name = "amber_percent", nullable = false)
    private int amberPercent;
    @Column(name = "escalate_to_role", length = 40)
    private String escalateToRole;

    public SlaPolicy() {
        // JPA; fields are populated through setters by the owning service
    }


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCaseType() {
        return caseType;
    }

    public void setCaseType(String caseType) {
        this.caseType = caseType;
    }

    public String getUrgency() {
        return urgency;
    }

    public void setUrgency(String urgency) {
        this.urgency = urgency;
    }

    public String getMilestone() {
        return milestone;
    }

    public void setMilestone(String milestone) {
        this.milestone = milestone;
    }

    public int getTargetMinutes() {
        return targetMinutes;
    }

    public void setTargetMinutes(int targetMinutes) {
        this.targetMinutes = targetMinutes;
    }

    public int getAmberPercent() {
        return amberPercent;
    }

    public void setAmberPercent(int amberPercent) {
        this.amberPercent = amberPercent;
    }

    public String getEscalateToRole() {
        return escalateToRole;
    }

    public void setEscalateToRole(String escalateToRole) {
        this.escalateToRole = escalateToRole;
    }
}
