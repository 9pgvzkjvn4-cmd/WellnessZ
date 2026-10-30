package com.wellnessz.e11.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Vaccination campaign (E11-F04); eligibility is JSON {all, jobCodes[], subsidiaries[]}. */
@Entity(name = "E11Campaign")
@Table(name = "campaign", schema = "travelhealth")
public class Campaign {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_ref")
    private String campaignRef;
    @Column(name = "name")
    private String name;
    @Column(name = "vaccine_code")
    private String vaccineCode;
    @Column(name = "eligibility", columnDefinition = "text")
    private String eligibility;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "site_codes")
    private String siteCodes;
    @Column(name = "target_uptake_pct")
    private BigDecimal targetUptakePct;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** New instance with a random id. */
    public static Campaign create() {
        Campaign e = new Campaign();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getCampaignRef() {
        return campaignRef;
    }

    public void setCampaignRef(String v) {
        this.campaignRef = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getVaccineCode() {
        return vaccineCode;
    }

    public void setVaccineCode(String v) {
        this.vaccineCode = v;
    }

    public String getEligibility() {
        return eligibility;
    }

    public void setEligibility(String v) {
        this.eligibility = v;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate v) {
        this.startDate = v;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate v) {
        this.endDate = v;
    }

    public String getSiteCodes() {
        return siteCodes;
    }

    public void setSiteCodes(String v) {
        this.siteCodes = v;
    }

    public BigDecimal getTargetUptakePct() {
        return targetUptakePct;
    }

    public void setTargetUptakePct(BigDecimal v) {
        this.targetUptakePct = v;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String v) {
        this.createdBy = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }
}
