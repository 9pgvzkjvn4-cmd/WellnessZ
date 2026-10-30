package com.wellnessz.e12.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Education session attendance (hearing conservation and others, E12-BL-007). */
@Entity(name = "E12EducationAttendance")
@Table(name = "education_attendance", schema = "promotion")
public class EducationAttendance {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "programme")
    private String programme;
    @Column(name = "session_date")
    private LocalDate sessionDate;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public EducationAttendance() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getProgramme() { return programme; }
    public void setProgramme(String v) { this.programme = v; }
    public LocalDate getSessionDate() { return sessionDate; }
    public void setSessionDate(LocalDate v) { this.sessionDate = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
