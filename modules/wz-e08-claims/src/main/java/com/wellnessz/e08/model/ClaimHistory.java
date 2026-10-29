package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Lifecycle and audit-relevant events of a claim (status changes with reason, holds, privilege). */
@Entity(name = "E08ClaimHistory")
@Table(name = "claim_history", schema = "claims")
public class ClaimHistory {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "event_type")
    private String eventType;
    @Column(name = "from_status")
    private String fromStatus;
    @Column(name = "to_status")
    private String toStatus;
    @Column(name = "reason")
    private String reason;
    @Column(name = "actor")
    private String actor;
    @Column(name = "occurred_at")
    private Instant occurredAt;

    public ClaimHistory() {
        // JPA; populated through setters by the owning service
    }

    public static ClaimHistory create() {
        ClaimHistory x = new ClaimHistory();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getFromStatus() { return fromStatus; }
    public void setFromStatus(String v) { this.fromStatus = v; }
    public String getToStatus() { return toStatus; }
    public void setToStatus(String v) { this.toStatus = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
}
