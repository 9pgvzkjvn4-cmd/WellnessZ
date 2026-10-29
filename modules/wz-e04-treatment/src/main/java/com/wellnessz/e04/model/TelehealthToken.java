package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Single-use participant join token (SHA-256 hash only). */
@Entity(name = "TxTelehealthToken")
@Table(name = "telehealth_token", schema = "treatment")
public class TelehealthToken {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "participant")
    private String participant;
    @Column(name = "username")
    private String username;
    @Column(name = "token_hash")
    private String tokenHash;
    @Column(name = "valid_from")
    private Instant validFrom;
    @Column(name = "valid_until")
    private Instant validUntil;
    @Column(name = "used_at")
    private Instant usedAt;
    @Column(name = "revoked")
    private boolean revoked;
    @Column(name = "created_at")
    private Instant createdAt;

    public TelehealthToken() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID v) { this.sessionId = v; }
    public String getParticipant() { return participant; }
    public void setParticipant(String v) { this.participant = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public Instant getValidFrom() { return validFrom; }
    public void setValidFrom(Instant v) { this.validFrom = v; }
    public Instant getValidUntil() { return validUntil; }
    public void setValidUntil(Instant v) { this.validUntil = v; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant v) { this.usedAt = v; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean v) { this.revoked = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
