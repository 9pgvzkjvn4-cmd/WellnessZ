package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Non-clinical timeline entry of a support case (separate restricted-class trail, insert-only). */
@Entity(name = "E09SupportEvent")
@Table(name = "support_event", schema = "mentalhealth")
public class SupportEvent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "summary")
    private String summary;
    @Column(name = "actor")
    private String actor;
    @Column(name = "occurred_at")
    private Instant occurredAt;

    public SupportEvent() {
        // JPA; populated through setters by the owning service
    }

    public static SupportEvent create() {
        SupportEvent x = new SupportEvent();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
}
