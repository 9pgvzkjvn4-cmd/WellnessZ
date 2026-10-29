package com.wellnessz.e07.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Invitation to a surveillance examination, optionally booked through the scheduling SPI. */
@Entity
@Table(name = "invitation", schema = "surveillance")
public class Invitation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "enrolment_id")
    private UUID enrolmentId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "sequence_no")
    private int sequenceNo;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "channel")
    private String channel;
    @Column(name = "status")
    private String status;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "appointment_starts_at")
    private Instant appointmentStartsAt;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Invitation() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEnrolmentId() { return enrolmentId; }
    public void setEnrolmentId(UUID v) { this.enrolmentId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public int getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(int v) { this.sequenceNo = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
    public Instant getAppointmentStartsAt() { return appointmentStartsAt; }
    public void setAppointmentStartsAt(Instant v) { this.appointmentStartsAt = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String v) { this.sentBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
