package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Generated campaign slot (day x station x time). */
@Entity
@Table(name = "campaign_slot", schema = "scheduling")
public class CampaignSlot {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "day")
    private LocalDate day;
    @Column(name = "station")
    private int station;
    @Column(name = "starts_at")
    private Instant startsAt;
    @Column(name = "ends_at")
    private Instant endsAt;
    @Column(name = "appointment_id")
    private UUID appointmentId;

    public CampaignSlot() {
        // JPA; populated through setters by the owning service
    }

    public static CampaignSlot create() {
        CampaignSlot x = new CampaignSlot();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate v) { this.day = v; }
    public int getStation() { return station; }
    public void setStation(int v) { this.station = v; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant v) { this.startsAt = v; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant v) { this.endsAt = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
}
