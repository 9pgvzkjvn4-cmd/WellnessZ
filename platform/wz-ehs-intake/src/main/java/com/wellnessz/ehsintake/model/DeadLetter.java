package com.wellnessz.ehsintake.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Invalid inbound incident payload parked for operator review (E01-BL-002 dead-letter queue). */
@Entity
@Table(name = "incident_dlq", schema = "intake")
public class DeadLetter {

    public enum Status { OPEN, REPLAYED, DISCARDED }

    @Id
    private UUID id;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(nullable = false, length = 100)
    private String source;
    @Column(nullable = false, length = 500)
    private String reason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status = Status.OPEN;
    @Column(name = "resolved_by", length = 200)
    private String resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected DeadLetter() {
    }

    public DeadLetter(String source, String reason, String payload, Instant now) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.reason = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.payload = payload;
        this.receivedAt = now;
    }

    public void resolve(Status status, String actor, Instant now) {
        this.status = status;
        this.resolvedBy = actor;
        this.resolvedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getSource() {
        return source;
    }

    public String getReason() {
        return reason;
    }

    public String getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }
}
