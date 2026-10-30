package com.wellnessz.e12.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Participation event (campaign app, screening, education session, wearable programme). */
@Entity(name = "E12Participation")
@Table(name = "participation", schema = "promotion")
public class Participation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "activity")
    private String activity;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @Column(name = "value")
    private BigDecimal value;
    @Column(name = "source")
    private String source;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "received_at")
    private Instant receivedAt;

    public Participation() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getActivity() { return activity; }
    public void setActivity(String v) { this.activity = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal v) { this.value = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
}
