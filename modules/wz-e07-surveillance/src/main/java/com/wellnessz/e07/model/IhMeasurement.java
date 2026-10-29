package com.wellnessz.e07.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Industrial hygiene measurement imported from HSE (noise dosimetry, air sampling …). */
@Entity
@Table(name = "ih_measurement", schema = "surveillance")
public class IhMeasurement {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "seg_code")
    private String segCode;
    @Column(name = "hazard_code")
    private String hazardCode;
    @Column(name = "measurement_type")
    private String measurementType;
    @Column(name = "value")
    private BigDecimal value;
    @Column(name = "unit")
    private String unit;
    @Column(name = "measured_on")
    private LocalDate measuredOn;
    @Column(name = "imported_by")
    private String importedBy;
    @Column(name = "imported_at")
    private Instant importedAt;

    public IhMeasurement() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getSegCode() { return segCode; }
    public void setSegCode(String v) { this.segCode = v; }
    public String getHazardCode() { return hazardCode; }
    public void setHazardCode(String v) { this.hazardCode = v; }
    public String getMeasurementType() { return measurementType; }
    public void setMeasurementType(String v) { this.measurementType = v; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public LocalDate getMeasuredOn() { return measuredOn; }
    public void setMeasuredOn(LocalDate v) { this.measuredOn = v; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String v) { this.importedBy = v; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant v) { this.importedAt = v; }
}
