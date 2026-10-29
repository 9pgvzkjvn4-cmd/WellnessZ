package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cold-chain temperature reading (manual or IoT). */
@Entity
@Table(name = "temperature_reading", schema = "scheduling")
public class TemperatureReading {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "equipment_id")
    private UUID equipmentId;
    @Column(name = "recorded_at")
    private Instant recordedAt;
    @Column(name = "temperature_c")
    private java.math.BigDecimal temperatureC;
    @Column(name = "source")
    private String source;
    @Column(name = "excursion")
    private boolean excursion;
    @Column(name = "recorded_by")
    private String recordedBy;

    public TemperatureReading() {
        // JPA; populated through setters by the owning service
    }

    public static TemperatureReading create() {
        TemperatureReading x = new TemperatureReading();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEquipmentId() { return equipmentId; }
    public void setEquipmentId(UUID v) { this.equipmentId = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
    public java.math.BigDecimal getTemperatureC() { return temperatureC; }
    public void setTemperatureC(java.math.BigDecimal v) { this.temperatureC = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public boolean isExcursion() { return excursion; }
    public void setExcursion(boolean v) { this.excursion = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
}
