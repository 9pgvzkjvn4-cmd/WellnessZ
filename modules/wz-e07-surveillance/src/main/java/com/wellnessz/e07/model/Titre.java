package com.wellnessz.e07.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Antibody titre result (e.g. anti-HBs after a hepatitis B course). */
@Entity
@Table(name = "titre", schema = "surveillance")
public class Titre {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "antigen")
    private String antigen;
    @Column(name = "value")
    private BigDecimal value;
    @Column(name = "unit")
    private String unit;
    @Column(name = "protective")
    private boolean protective;
    @Column(name = "tested_on")
    private LocalDate testedOn;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public Titre() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getAntigen() { return antigen; }
    public void setAntigen(String v) { this.antigen = v; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public boolean isProtective() { return protective; }
    public void setProtective(boolean v) { this.protective = v; }
    public LocalDate getTestedOn() { return testedOn; }
    public void setTestedOn(LocalDate v) { this.testedOn = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
