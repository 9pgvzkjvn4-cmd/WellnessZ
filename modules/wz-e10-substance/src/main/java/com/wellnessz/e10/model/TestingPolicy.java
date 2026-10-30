package com.wellnessz.e10.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Testing policy of one role group (E10-F01); approval tracked for E10-BL-010. */
@Entity(name = "E10Policy")
@Table(name = "policy", schema = "substance")
public class TestingPolicy {

    @Id
    @Column(name = "role_group")
    private String roleGroup;
    @Column(name = "job_codes")
    private String jobCodes;
    @Column(name = "panels")
    private String panels;
    @Column(name = "random_drug_rate_pct")
    private BigDecimal randomDrugRatePct;
    @Column(name = "random_alcohol_rate_pct")
    private BigDecimal randomAlcoholRatePct;
    @Column(name = "alcohol_window_hours")
    private int alcoholWindowHours;
    @Column(name = "drug_window_hours")
    private int drugWindowHours;
    @Column(name = "lead_time_minutes")
    private int leadTimeMinutes;
    @Column(name = "jurisdiction")
    private String jurisdiction;
    @Column(name = "consent_required")
    private boolean consentRequired;
    @Column(name = "approval_status")
    private String approvalStatus;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public TestingPolicy() {
        // JPA; populated through setters by the owning service
    }

    public String getRoleGroup() { return roleGroup; }
    public void setRoleGroup(String v) { this.roleGroup = v; }
    public String getJobCodes() { return jobCodes; }
    public void setJobCodes(String v) { this.jobCodes = v; }
    public String getPanels() { return panels; }
    public void setPanels(String v) { this.panels = v; }
    public BigDecimal getRandomDrugRatePct() { return randomDrugRatePct; }
    public void setRandomDrugRatePct(BigDecimal v) { this.randomDrugRatePct = v; }
    public BigDecimal getRandomAlcoholRatePct() { return randomAlcoholRatePct; }
    public void setRandomAlcoholRatePct(BigDecimal v) { this.randomAlcoholRatePct = v; }
    public int getAlcoholWindowHours() { return alcoholWindowHours; }
    public void setAlcoholWindowHours(int v) { this.alcoholWindowHours = v; }
    public int getDrugWindowHours() { return drugWindowHours; }
    public void setDrugWindowHours(int v) { this.drugWindowHours = v; }
    public int getLeadTimeMinutes() { return leadTimeMinutes; }
    public void setLeadTimeMinutes(int v) { this.leadTimeMinutes = v; }
    public String getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(String v) { this.jurisdiction = v; }
    public boolean isConsentRequired() { return consentRequired; }
    public void setConsentRequired(boolean v) { this.consentRequired = v; }
    public String getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(String v) { this.approvalStatus = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { this.approvedBy = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
