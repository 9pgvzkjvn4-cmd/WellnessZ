package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Append-only transition of a capture session (codes only – never values or names). */
@Entity(name = "E33CaptureEvent")
@Table(name = "capture_event", schema = "devicecap")
public class CaptureEvent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @Column(name = "actor")
    private String actor;
    @Column(name = "from_state")
    private String fromState;
    @Column(name = "to_state")
    private String toState;
    @Column(name = "event")
    private String event;
    @Column(name = "detail")
    private String detail;

    protected CaptureEvent() {
        // JPA
    }

    public CaptureEvent(UUID sessionId, Instant occurredAt, String actor, String fromState, String toState, String event, String detail) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.occurredAt = occurredAt;
        this.actor = actor == null ? "system" : actor.length() > 200 ? actor.substring(0, 200) : actor;
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.detail = detail == null ? null : detail.length() > 200 ? detail.substring(0, 200) : detail;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getFromState() { return fromState; }
    public String getToState() { return toState; }
    public String getEvent() { return event; }
    public String getDetail() { return detail; }
}
