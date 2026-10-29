package com.wellnessz.e07.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Role / hazard immunisation matrix entry (E07-F05). */
@Entity
@Table(name = "immunisation_requirement", schema = "surveillance")
public class ImmunisationRequirement {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "hazard_code")
    private String hazardCode;
    @Column(name = "vaccine_code")
    private String vaccineCode;
    @Column(name = "vaccine_name")
    private String vaccineName;
    @Column(name = "doses")
    private int doses;
    @Column(name = "schedule_months")
    private String scheduleMonths;
    @Column(name = "booster_months")
    private Integer boosterMonths;
    @Column(name = "titre_required")
    private boolean titreRequired;
    @Column(name = "mandatory")
    private boolean mandatory;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ImmunisationRequirement() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getJobCode() { return jobCode; }
    public void setJobCode(String v) { this.jobCode = v; }
    public String getHazardCode() { return hazardCode; }
    public void setHazardCode(String v) { this.hazardCode = v; }
    public String getVaccineCode() { return vaccineCode; }
    public void setVaccineCode(String v) { this.vaccineCode = v; }
    public String getVaccineName() { return vaccineName; }
    public void setVaccineName(String v) { this.vaccineName = v; }
    public int getDoses() { return doses; }
    public void setDoses(int v) { this.doses = v; }
    public String getScheduleMonths() { return scheduleMonths; }
    public void setScheduleMonths(String v) { this.scheduleMonths = v; }
    public Integer getBoosterMonths() { return boosterMonths; }
    public void setBoosterMonths(Integer v) { this.boosterMonths = v; }
    public boolean isTitreRequired() { return titreRequired; }
    public void setTitreRequired(boolean v) { this.titreRequired = v; }
    public boolean isMandatory() { return mandatory; }
    public void setMandatory(boolean v) { this.mandatory = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
