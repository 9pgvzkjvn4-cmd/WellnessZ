package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Consent-gated set of documents disclosed to an insurer / TPA for a claim (E08-F03). */
@Entity(name = "E08DisclosureSet")
@Table(name = "disclosure_set", schema = "claims")
public class DisclosureSet {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "recipient_code")
    private String recipientCode;
    @Column(name = "purpose")
    private String purpose;
    @Column(name = "status")
    private String status;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoked_by")
    private String revokedBy;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public DisclosureSet() {
        // JPA; populated through setters by the owning service
    }

    public static DisclosureSet create() {
        DisclosureSet x = new DisclosureSet();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public String getRecipientCode() { return recipientCode; }
    public void setRecipientCode(String v) { this.recipientCode = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String v) { this.sentBy = v; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant v) { this.revokedAt = v; }
    public String getRevokedBy() { return revokedBy; }
    public void setRevokedBy(String v) { this.revokedBy = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
