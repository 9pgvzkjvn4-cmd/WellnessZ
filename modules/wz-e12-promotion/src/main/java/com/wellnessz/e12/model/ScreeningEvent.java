package com.wellnessz.e12.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Screening event (E12-F02). */
@Entity(name = "E12ScreeningEvent")
@Table(name = "screening_event", schema = "promotion")
public class ScreeningEvent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "event_ref")
    private String eventRef;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "event_date")
    private LocalDate eventDate;
    @Column(name = "status")
    private String status;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ScreeningEvent() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEventRef() { return eventRef; }
    public void setEventRef(String v) { this.eventRef = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public LocalDate getEventDate() { return eventDate; }
    public void setEventDate(LocalDate v) { this.eventDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
