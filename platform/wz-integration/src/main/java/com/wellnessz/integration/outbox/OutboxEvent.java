package com.wellnessz.integration.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Row of the transactional outbox: written in the same transaction as the business change, relayed afterwards. */
@Entity
@Table(name = "outbox_event", schema = "platform")
public class OutboxEvent {

    public enum Status { PENDING, PUBLISHED, FAILED, DEAD }

    @Id
    private UUID id;
    @Column(name = "event_type", nullable = false, length = 200)
    private String eventType;
    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, length = 200)
    private String aggregateId;
    @Column(nullable = false, length = 100)
    private String destination;
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "last_error", length = 1000)
    private String lastError;
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected OutboxEvent() {
    }

    public OutboxEvent(String eventType, String aggregateType, String aggregateId, String destination, String payload,
                       String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.destination = destination;
        this.payload = payload;
        this.correlationId = correlationId;
        this.status = Status.PENDING;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    public void markPublished(Instant now) {
        this.status = Status.PUBLISHED;
        this.publishedAt = now;
        this.lastError = null;
    }

    public void markFailed(String error, Instant now, int maxAttempts) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
        if (attempts >= maxAttempts) {
            this.status = Status.DEAD;
        } else {
            this.status = Status.FAILED;
            // exponential back-off: 2^attempts seconds, capped at 1 hour
            this.nextAttemptAt = now.plusSeconds(Math.min(3600, 1L << Math.min(attempts, 12)));
        }
    }

    public void resetForReplay(Instant now) {
        this.status = Status.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getDestination() {
        return destination;
    }

    public String getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }
}
