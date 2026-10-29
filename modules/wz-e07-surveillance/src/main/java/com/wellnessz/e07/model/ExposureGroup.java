package com.wellnessz.e07.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Similar exposure group (SEG) linking job codes at a location to a hazard and its action level. */
@Entity
@Table(name = "exposure_group", schema = "surveillance")
public class ExposureGroup {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "location_code")
    private String locationCode;
    @Column(name = "job_codes")
    private String jobCodes;
    @Column(name = "hazard_code")
    private String hazardCode;
    @Column(name = "unit")
    private String unit;
    @Column(name = "action_level")
    private BigDecimal actionLevel;
    @Column(name = "limit_value")
    private BigDecimal limitValue;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ExposureGroup() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String v) { this.locationCode = v; }
    public String getJobCodes() { return jobCodes; }
    public void setJobCodes(String v) { this.jobCodes = v; }
    public String getHazardCode() { return hazardCode; }
    public void setHazardCode(String v) { this.hazardCode = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public BigDecimal getActionLevel() { return actionLevel; }
    public void setActionLevel(BigDecimal v) { this.actionLevel = v; }
    public BigDecimal getLimitValue() { return limitValue; }
    public void setLimitValue(BigDecimal v) { this.limitValue = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
