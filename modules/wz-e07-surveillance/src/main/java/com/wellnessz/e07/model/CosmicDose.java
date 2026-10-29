package com.wellnessz.e07.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Annual cosmic radiation dose of a crew member. */
@Entity
@Table(name = "cosmic_dose", schema = "surveillance")
public class CosmicDose {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "dose_year")
    private int doseYear;
    @Column(name = "dose_msv")
    private BigDecimal doseMsv;
    @Column(name = "source")
    private String source;
    @Column(name = "imported_by")
    private String importedBy;
    @Column(name = "imported_at")
    private Instant importedAt;
    @Column(name = "alerted")
    private boolean alerted;

    public CosmicDose() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public int getDoseYear() { return doseYear; }
    public void setDoseYear(int v) { this.doseYear = v; }
    public BigDecimal getDoseMsv() { return doseMsv; }
    public void setDoseMsv(BigDecimal v) { this.doseMsv = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String v) { this.importedBy = v; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant v) { this.importedAt = v; }
    public boolean isAlerted() { return alerted; }
    public void setAlerted(boolean v) { this.alerted = v; }
}
