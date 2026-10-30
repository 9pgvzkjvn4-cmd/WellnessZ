package com.wellnessz.e11.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Daily symptom check-in of a monitored contact (E11-F02). */
@Entity(name = "E11CheckIn")
@Table(name = "check_in", schema = "travelhealth")
public class CheckIn {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "contact_id")
    private UUID contactId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "due_on")
    private LocalDate dueOn;
    @Column(name = "status")
    private String status;
    @Column(name = "symptoms")
    private String symptoms;
    @Column(name = "temperature_c")
    private BigDecimal temperatureC;
    @Column(name = "symptomatic")
    private boolean symptomatic;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "reminded_at")
    private Instant remindedAt;

    /** New instance with a random id. */
    public static CheckIn create() {
        CheckIn e = new CheckIn();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getContactId() {
        return contactId;
    }

    public void setContactId(UUID v) {
        this.contactId = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public void setDueOn(LocalDate v) {
        this.dueOn = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        this.status = v;
    }

    public String getSymptoms() {
        return symptoms;
    }

    public void setSymptoms(String v) {
        this.symptoms = v;
    }

    public BigDecimal getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(BigDecimal v) {
        this.temperatureC = v;
    }

    public boolean isSymptomatic() {
        return symptomatic;
    }

    public void setSymptomatic(boolean v) {
        this.symptomatic = v;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant v) {
        this.submittedAt = v;
    }

    public Instant getRemindedAt() {
        return remindedAt;
    }

    public void setRemindedAt(Instant v) {
        this.remindedAt = v;
    }
}
