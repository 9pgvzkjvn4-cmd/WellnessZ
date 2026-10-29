package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Complexity inputs captured by the clinician (E02-F06). */
@Entity
@Table(name = "case_risk_flags", schema = "triage")
public class RiskFlags {

    @Id
    @Column(name = "case_id")
    private UUID caseId;
    @Column(nullable = false)
    private boolean comorbidity;
    @Column(name = "psychosocial_positive", nullable = false)
    private boolean psychosocialScreenPositive;
    @Column(name = "claim_or_litigation", nullable = false)
    private boolean claimOrLitigation;
    @Column(name = "primary_icd10", length = 10)
    private String primaryIcd10;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RiskFlags() {
        // JPA; fields are populated through setters by the owning service
    }


    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public boolean isComorbidity() {
        return comorbidity;
    }

    public void setComorbidity(boolean comorbidity) {
        this.comorbidity = comorbidity;
    }

    public boolean isPsychosocialScreenPositive() {
        return psychosocialScreenPositive;
    }

    public void setPsychosocialScreenPositive(boolean psychosocialScreenPositive) {
        this.psychosocialScreenPositive = psychosocialScreenPositive;
    }

    public boolean isClaimOrLitigation() {
        return claimOrLitigation;
    }

    public void setClaimOrLitigation(boolean claimOrLitigation) {
        this.claimOrLitigation = claimOrLitigation;
    }

    public String getPrimaryIcd10() {
        return primaryIcd10;
    }

    public void setPrimaryIcd10(String primaryIcd10) {
        this.primaryIcd10 = primaryIcd10;
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
