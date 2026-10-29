package com.wellnessz.masterdata.fitness;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Physical / environmental / safety demands of a job code (E05-F02), used to match restrictions to modified duties. */
@Entity
@Table(name = "job_demand_profile", schema = "masterdata")
public class JobDemandProfile {

    @Id
    @Column(name = "job_code", length = 64)
    private String jobCode;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(nullable = false, length = 20)
    private String unit;
    /** JSON object {dimension: number | boolean}. */
    @Column(nullable = false, columnDefinition = "text")
    private String demands;
    @Column(length = 10)
    private String sitting;
    @Column(name = "validated_by", length = 200)
    private String validatedBy;
    @Column(name = "validated_at")
    private Instant validatedAt;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected JobDemandProfile() {
    }

    public JobDemandProfile(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getJobCode() { return jobCode; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public String getDemands() { return demands; }
    public void setDemands(String demands) { this.demands = demands; }
    public String getSitting() { return sitting; }
    public void setSitting(String sitting) { this.sitting = sitting; }
    public String getValidatedBy() { return validatedBy; }
    public void setValidatedBy(String validatedBy) { this.validatedBy = validatedBy; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant validatedAt) { this.validatedAt = validatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
