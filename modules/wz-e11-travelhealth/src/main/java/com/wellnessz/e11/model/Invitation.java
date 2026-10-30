package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Campaign invitation and the employee's response / booking (E11-F04). */
@Entity(name = "E11Invitation")
@Table(name = "campaign_invitation", schema = "travelhealth")
public class Invitation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "invited_at")
    private Instant invitedAt;
    @Column(name = "response")
    private String response;
    @Column(name = "responded_at")
    private Instant respondedAt;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "appointment_at")
    private Instant appointmentAt;
    @Column(name = "appointment_site")
    private String appointmentSite;

    /** New instance with a random id. */
    public static Invitation create() {
        Invitation e = new Invitation();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public void setCampaignId(UUID v) {
        this.campaignId = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public Instant getInvitedAt() {
        return invitedAt;
    }

    public void setInvitedAt(Instant v) {
        this.invitedAt = v;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String v) {
        this.response = v;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(Instant v) {
        this.respondedAt = v;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(UUID v) {
        this.appointmentId = v;
    }

    public Instant getAppointmentAt() {
        return appointmentAt;
    }

    public void setAppointmentAt(Instant v) {
        this.appointmentAt = v;
    }

    public String getAppointmentSite() {
        return appointmentSite;
    }

    public void setAppointmentSite(String v) {
        this.appointmentSite = v;
    }
}
