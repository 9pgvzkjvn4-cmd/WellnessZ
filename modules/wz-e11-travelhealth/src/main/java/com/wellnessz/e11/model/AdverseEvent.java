package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Adverse event following immunisation (E11-F04); actions are encrypted. */
@Entity(name = "E11AdverseEvent")
@Table(name = "adverse_event", schema = "travelhealth")
public class AdverseEvent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "administration_id")
    private UUID administrationId;
    @Column(name = "event")
    private String event;
    @Column(name = "severity")
    private String severity;
    @Column(name = "onset_at")
    private Instant onsetAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "actions", columnDefinition = "text")
    private String actions;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    /** New instance with a random id. */
    public static AdverseEvent create() {
        AdverseEvent e = new AdverseEvent();
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

    public UUID getAdministrationId() {
        return administrationId;
    }

    public void setAdministrationId(UUID v) {
        this.administrationId = v;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String v) {
        this.event = v;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String v) {
        this.severity = v;
    }

    public Instant getOnsetAt() {
        return onsetAt;
    }

    public void setOnsetAt(Instant v) {
        this.onsetAt = v;
    }

    public String getActions() {
        return actions;
    }

    public void setActions(String v) {
        this.actions = v;
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
