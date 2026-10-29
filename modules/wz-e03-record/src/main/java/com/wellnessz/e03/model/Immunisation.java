package com.wellnessz.e03.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Immunisation record (FHIR Immunization). */
@Entity
@Table(name = "immunisation", schema = "ohrecord")
public class Immunisation {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "vaccine")
    private String vaccine;
    @Column(name = "code_system")
    private String codeSystem;
    @Column(name = "code")
    private String code;
    @Column(name = "administered_on")
    private LocalDate administeredOn;
    @Column(name = "dose_number")
    private Integer doseNumber;
    @Column(name = "lot_number")
    private String lotNumber;
    @Column(name = "status")
    private String status;
    @Column(name = "next_due_date")
    private LocalDate nextDueDate;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public Immunisation() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getVaccine() {
        return vaccine;
    }

    public void setVaccine(String vaccine) {
        this.vaccine = vaccine;
    }

    public String getCodeSystem() {
        return codeSystem;
    }

    public void setCodeSystem(String codeSystem) {
        this.codeSystem = codeSystem;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public LocalDate getAdministeredOn() {
        return administeredOn;
    }

    public void setAdministeredOn(LocalDate administeredOn) {
        this.administeredOn = administeredOn;
    }

    public Integer getDoseNumber() {
        return doseNumber;
    }

    public void setDoseNumber(Integer doseNumber) {
        this.doseNumber = doseNumber;
    }

    public String getLotNumber() {
        return lotNumber;
    }

    public void setLotNumber(String lotNumber) {
        this.lotNumber = lotNumber;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDate getNextDueDate() {
        return nextDueDate;
    }

    public void setNextDueDate(LocalDate nextDueDate) {
        this.nextDueDate = nextDueDate;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }
}
