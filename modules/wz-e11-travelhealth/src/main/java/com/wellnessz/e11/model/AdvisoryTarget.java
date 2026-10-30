package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Targeted employee of an advisory with acknowledgement (E11-F05). */
@Entity(name = "E11AdvisoryTarget")
@Table(name = "advisory_target", schema = "travelhealth")
public class AdvisoryTarget {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "advisory_id")
    private UUID advisoryId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "reason")
    private String reason;
    @Column(name = "duty_ref")
    private String dutyRef;
    @Column(name = "notified_at")
    private Instant notifiedAt;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    /** New instance with a random id. */
    public static AdvisoryTarget create() {
        AdvisoryTarget e = new AdvisoryTarget();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getAdvisoryId() {
        return advisoryId;
    }

    public void setAdvisoryId(UUID v) {
        this.advisoryId = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = v;
    }

    public String getDutyRef() {
        return dutyRef;
    }

    public void setDutyRef(String v) {
        this.dutyRef = v;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }

    public void setNotifiedAt(Instant v) {
        this.notifiedAt = v;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant v) {
        this.acknowledgedAt = v;
    }
}
