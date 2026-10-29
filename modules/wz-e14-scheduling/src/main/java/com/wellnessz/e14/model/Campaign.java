package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Campaign / mobile-clinic day plan (E14-F02). */
@Entity
@Table(name = "campaign", schema = "scheduling")
public class Campaign {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "name")
    private String name;
    @Column(name = "campaign_type")
    private String campaignType;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "appointment_type")
    private String appointmentType;
    @Column(name = "days")
    private String days;
    @Column(name = "day_start")
    private LocalTime dayStart;
    @Column(name = "day_end")
    private LocalTime dayEnd;
    @Column(name = "slot_minutes")
    private int slotMinutes;
    @Column(name = "stations")
    private int stations;
    @Column(name = "device_kit")
    private String deviceKit;
    @Column(name = "staff")
    private String staff;
    @Column(name = "target_count")
    private int targetCount;
    @Column(name = "status")
    private String status;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version @Column(name = "version")
    private long version;

    public Campaign() {
        // JPA; populated through setters by the owning service
    }

    public static Campaign create() {
        Campaign x = new Campaign();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getCampaignType() { return campaignType; }
    public void setCampaignType(String v) { this.campaignType = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getAppointmentType() { return appointmentType; }
    public void setAppointmentType(String v) { this.appointmentType = v; }
    public String getDays() { return days; }
    public void setDays(String v) { this.days = v; }
    public LocalTime getDayStart() { return dayStart; }
    public void setDayStart(LocalTime v) { this.dayStart = v; }
    public LocalTime getDayEnd() { return dayEnd; }
    public void setDayEnd(LocalTime v) { this.dayEnd = v; }
    public int getSlotMinutes() { return slotMinutes; }
    public void setSlotMinutes(int v) { this.slotMinutes = v; }
    public int getStations() { return stations; }
    public void setStations(int v) { this.stations = v; }
    public String getDeviceKit() { return deviceKit; }
    public void setDeviceKit(String v) { this.deviceKit = v; }
    public String getStaff() { return staff; }
    public void setStaff(String v) { this.staff = v; }
    public int getTargetCount() { return targetCount; }
    public void setTargetCount(int v) { this.targetCount = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
}
