package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A recorded surveillance examination (history for compliance and audit packs). */
@Entity
@Table(name = "completion", schema = "surveillance")
public class Completion {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "enrolment_id")
    private UUID enrolmentId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "programme_code")
    private String programmeCode;
    @Column(name = "performed_on")
    private LocalDate performedOn;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Column(name = "result_ids")
    private String resultIds;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public Completion() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEnrolmentId() { return enrolmentId; }
    public void setEnrolmentId(UUID v) { this.enrolmentId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getProgrammeCode() { return programmeCode; }
    public void setProgrammeCode(String v) { this.programmeCode = v; }
    public LocalDate getPerformedOn() { return performedOn; }
    public void setPerformedOn(LocalDate v) { this.performedOn = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID v) { this.encounterId = v; }
    public String getResultIds() { return resultIds; }
    public void setResultIds(String v) { this.resultIds = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
