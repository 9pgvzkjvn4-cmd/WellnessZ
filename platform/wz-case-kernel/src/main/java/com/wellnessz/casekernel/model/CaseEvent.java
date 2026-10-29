package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Case timeline entry. {@code summary} is non-clinical by convention (e.g. "Triaged URGENT", "Escalated to CMO"). */
@Entity
@Table(name = "case_event", schema = "casemgmt")
public class CaseEvent {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "event_type", nullable = false, length = 60)
    private String type;
    @Column(nullable = false, length = 500)
    private String summary;
    @Column(nullable = false, length = 200)
    private String actor;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected CaseEvent() {
    }

    public CaseEvent(UUID caseId, String type, String summary, String actor, Instant now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.type = type;
        this.summary = summary.length() > 500 ? summary.substring(0, 500) : summary;
        this.actor = actor;
        this.occurredAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getType() {
        return type;
    }

    public String getSummary() {
        return summary;
    }

    public String getActor() {
        return actor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void moveTo(UUID target) {
        this.caseId = target;
    }
}
