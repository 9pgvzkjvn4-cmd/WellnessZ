package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Secure claim message (screened for clinical content, encrypted at rest). */
@Entity(name = "E08ClaimMessage")
@Table(name = "claim_message", schema = "claims")
public class ClaimMessage {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "sender")
    private String sender;
    @Column(name = "sender_role")
    private String senderRole;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "body", columnDefinition = "text")
    private String body;
    @Column(name = "sent_at")
    private Instant sentAt;

    public ClaimMessage() {
        // JPA; populated through setters by the owning service
    }

    public static ClaimMessage create() {
        ClaimMessage x = new ClaimMessage();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public String getSender() { return sender; }
    public void setSender(String v) { this.sender = v; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String v) { this.senderRole = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
}
