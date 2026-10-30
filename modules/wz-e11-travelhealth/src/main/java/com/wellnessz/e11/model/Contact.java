package com.wellnessz.e11.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Traced contact of a communicable-disease case (E11-F02). */
@Entity(name = "E11Contact")
@Table(name = "contact", schema = "travelhealth")
public class Contact {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "cd_case_id")
    private UUID cdCaseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "contact_type")
    private String contactType;
    @Column(name = "duty_ref")
    private String dutyRef;
    @Column(name = "overlap_minutes")
    private int overlapMinutes;
    @Column(name = "risk_level")
    private String riskLevel;
    @Column(name = "notified_at")
    private Instant notifiedAt;
    @Column(name = "monitoring_until")
    private LocalDate monitoringUntil;
    @Column(name = "last_check_in")
    private Instant lastCheckIn;
    @Column(name = "symptomatic")
    private boolean symptomatic;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** New instance with a random id. */
    public static Contact create() {
        Contact e = new Contact();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getCdCaseId() {
        return cdCaseId;
    }

    public void setCdCaseId(UUID v) {
        this.cdCaseId = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getContactType() {
        return contactType;
    }

    public void setContactType(String v) {
        this.contactType = v;
    }

    public String getDutyRef() {
        return dutyRef;
    }

    public void setDutyRef(String v) {
        this.dutyRef = v;
    }

    public int getOverlapMinutes() {
        return overlapMinutes;
    }

    public void setOverlapMinutes(int v) {
        this.overlapMinutes = v;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String v) {
        this.riskLevel = v;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(Instant v) {
        this.notifiedAt = v;
    }

    public LocalDate getMonitoringUntil() {
        return monitoringUntil;
    }

    public void setMonitoringUntil(LocalDate v) {
        this.monitoringUntil = v;
    }

    public Instant getLastCheckIn() {
        return lastCheckIn;
    }

    public void setLastCheckIn(Instant v) {
        this.lastCheckIn = v;
    }

    public boolean isSymptomatic() {
        return symptomatic;
    }

    public void setSymptomatic(boolean v) {
        this.symptomatic = v;
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
