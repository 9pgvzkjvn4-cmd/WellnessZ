package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Waitlist entry; a cancellation offers the freed slot to the first matching entry. */
@Entity
@Table(name = "waitlist_entry", schema = "scheduling")
public class WaitlistEntry {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "appointment_type")
    private String appointmentType;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "earliest")
    private LocalDate earliest;
    @Column(name = "latest")
    private LocalDate latest;
    @Column(name = "priority")
    private int priority;
    @Column(name = "status")
    private String status;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "offered_appointment_id")
    private UUID offeredAppointmentId;
    @Column(name = "offered_starts_at")
    private Instant offeredStartsAt;
    @Column(name = "offered_at")
    private Instant offeredAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public WaitlistEntry() {
        // JPA; populated through setters by the owning service
    }

    public static WaitlistEntry create() {
        WaitlistEntry x = new WaitlistEntry();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getAppointmentType() { return appointmentType; }
    public void setAppointmentType(String v) { this.appointmentType = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public LocalDate getEarliest() { return earliest; }
    public void setEarliest(LocalDate v) { this.earliest = v; }
    public LocalDate getLatest() { return latest; }
    public void setLatest(LocalDate v) { this.latest = v; }
    public int getPriority() { return priority; }
    public void setPriority(int v) { this.priority = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public UUID getOfferedAppointmentId() { return offeredAppointmentId; }
    public void setOfferedAppointmentId(UUID v) { this.offeredAppointmentId = v; }
    public Instant getOfferedStartsAt() { return offeredStartsAt; }
    public void setOfferedStartsAt(Instant v) { this.offeredStartsAt = v; }
    public Instant getOfferedAt() { return offeredAt; }
    public void setOfferedAt(Instant v) { this.offeredAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
