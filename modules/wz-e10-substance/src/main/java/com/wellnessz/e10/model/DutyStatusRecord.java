package com.wellnessz.e10.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Duty status of an employee in the programme – the only E10 information HR / crew scheduling see. */
@Entity(name = "E10DutyStatus")
@Table(name = "duty_status", schema = "substance")
public class DutyStatusRecord {

    @Id
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "status")
    private String status;
    @Column(name = "since")
    private Instant since;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public DutyStatusRecord() {
        // JPA; populated through setters by the owning service
    }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getSince() { return since; }
    public void setSince(Instant v) { this.since = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
