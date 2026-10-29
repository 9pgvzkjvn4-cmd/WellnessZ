package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cold-chain temperature excursion. */
@Entity
@Table(name = "excursion", schema = "scheduling")
public class Excursion {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "equipment_id")
    private UUID equipmentId;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "min_c")
    private java.math.BigDecimal minC;
    @Column(name = "max_c")
    private java.math.BigDecimal maxC;
    @Column(name = "status")
    private String status;
    @Column(name = "action")
    private String action;
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "closed_by")
    private String closedBy;
    @Column(name = "closed_at")
    private Instant closedAt;

    public Excursion() {
        // JPA; populated through setters by the owning service
    }

    public static Excursion create() {
        Excursion x = new Excursion();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEquipmentId() { return equipmentId; }
    public void setEquipmentId(UUID v) { this.equipmentId = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { this.endedAt = v; }
    public java.math.BigDecimal getMinC() { return minC; }
    public void setMinC(java.math.BigDecimal v) { this.minC = v; }
    public java.math.BigDecimal getMaxC() { return maxC; }
    public void setMaxC(java.math.BigDecimal v) { this.maxC = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(String v) { this.acknowledgedBy = v; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant v) { this.acknowledgedAt = v; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String v) { this.closedBy = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
