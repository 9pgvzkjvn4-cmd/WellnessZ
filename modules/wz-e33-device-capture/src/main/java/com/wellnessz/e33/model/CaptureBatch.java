package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Screening-day batch on a nurse tablet (E33-F10): a queue of employees with pre-created sessions. */
@Entity(name = "E33CaptureBatch")
@Table(name = "capture_batch", schema = "devicecap")
public class CaptureBatch {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "tenant_code")
    private String tenantCode = "GROUP";
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "screening_event_id")
    private String screeningEventId;
    @Column(name = "form_code")
    private String formCode;
    @Column(name = "device_instance_ids")
    private String deviceInstanceIds;
    @Column(name = "operator")
    private String operator;
    @Column(name = "status")
    private String status;
    @Column(name = "entries")
    private int entries;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public CaptureBatch() {
        // JPA
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getTenantCode() { return tenantCode; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getScreeningEventId() { return screeningEventId; }
    public void setScreeningEventId(String v) { this.screeningEventId = v; }
    public String getFormCode() { return formCode; }
    public void setFormCode(String v) { this.formCode = v; }
    public String getDeviceInstanceIds() { return deviceInstanceIds; }
    public void setDeviceInstanceIds(String v) { this.deviceInstanceIds = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getEntries() { return entries; }
    public void setEntries(int v) { this.entries = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
