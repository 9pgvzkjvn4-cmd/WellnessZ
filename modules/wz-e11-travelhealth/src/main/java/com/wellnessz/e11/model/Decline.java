package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Clinician-recorded decline (E11-F04); the reason is encrypted. */
@Entity(name = "E11Decline")
@Table(name = "campaign_decline", schema = "travelhealth")
public class Decline {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "employee_id")
    private String employeeId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason", columnDefinition = "text")
    private String reason;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    /** New instance with a random id. */
    public static Decline create() {
        Decline e = new Decline();
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

    public String getReason() {
        return reason;
    }

    public void setReason(String v) {
        this.reason = v;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String v) {
        this.recordedBy = v;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant v) {
        this.recordedAt = v;
    }
}
