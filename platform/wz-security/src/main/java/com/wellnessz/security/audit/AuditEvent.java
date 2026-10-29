package com.wellnessz.security.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Immutable, hash-chained audit record (SOC 2 CC7.2 / CC7.3, HIPAA §164.312(b)).
 * The table is append-only at database level (UPDATE/DELETE/TRUNCATE blocked by trigger and grants);
 * each row's hash covers the previous row's hash so any tampering breaks the chain.
 */
@Entity
@Table(name = "audit_event", schema = "platform")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(nullable = false, length = 200)
    private String actor;
    @Column(name = "actor_roles", length = 500)
    private String actorRoles;
    @Column(nullable = false, length = 100)
    private String action;
    @Column(name = "resource_type", length = 100)
    private String resourceType;
    @Column(name = "resource_id", length = 200)
    private String resourceId;
    @Column(nullable = false, length = 20)
    private String outcome;
    @Column(length = 500)
    private String reason;
    @Column(name = "correlation_id", length = 64)
    private String correlationId;
    @Column(name = "client_ip", length = 64)
    private String clientIp;
    @Column(name = "prev_hash", nullable = false, length = 64)
    private String prevHash;
    @Column(nullable = false, length = 64)
    private String hash;

    protected AuditEvent() {
    }

    public AuditEvent(Instant occurredAt, String actor, String actorRoles, String action, String resourceType,
                      String resourceId, String outcome, String reason, String correlationId, String clientIp) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.actorRoles = actorRoles;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.outcome = outcome;
        this.reason = reason;
        this.correlationId = correlationId;
        this.clientIp = clientIp;
    }

    /** Canonical, order-stable representation that the hash covers. */
    public String canonical() {
        return String.join("|", String.valueOf(occurredAt.toEpochMilli()), actor, n(actorRoles), action, n(resourceType),
                n(resourceId), outcome, n(reason), n(correlationId), n(clientIp));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    void chain(String prevHash, String hash) {
        this.prevHash = prevHash;
        this.hash = hash;
    }

    public Long getId() {
        return id;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getActor() {
        return actor;
    }

    public String getActorRoles() {
        return actorRoles;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public String getHash() {
        return hash;
    }
}
