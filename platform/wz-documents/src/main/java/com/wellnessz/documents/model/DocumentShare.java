package com.wellnessz.documents.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Grant of read access to one document for the employee, their manager, HR or a partner organisation. */
@Entity
@Table(name = "document_share", schema = "docs")
public class DocumentShare {

    /** INSURER = insurer / TPA organisation (increment 4, E08), gated by the employee's INSURER_SHARING consent. */
    public enum Target { EMPLOYEE, MANAGER, HR, PARTNER, INSURER }

    @Id
    private UUID id;
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Target target;
    @Column(length = 200)
    private String recipient;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "shared_by", nullable = false, length = 200)
    private String sharedBy;
    @Column(name = "shared_at", nullable = false)
    private Instant sharedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoked_by", length = 200)
    private String revokedBy;
    @Column(name = "revoke_reason", length = 200)
    private String revokeReason;

    protected DocumentShare() {
    }

    public DocumentShare(UUID documentId, Target target, String recipient, UUID consentId, String sharedBy, Instant at) {
        this.id = UUID.randomUUID();
        this.documentId = documentId;
        this.target = target;
        this.recipient = recipient;
        this.consentId = consentId;
        this.sharedBy = sharedBy;
        this.sharedAt = at;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public void revoke(String by, String reason, Instant at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokedBy = by;
            this.revokeReason = reason;
        }
    }

    public UUID getId() { return id; }
    public UUID getDocumentId() { return documentId; }
    public Target getTarget() { return target; }
    public String getRecipient() { return recipient; }
    public UUID getConsentId() { return consentId; }
    public String getSharedBy() { return sharedBy; }
    public Instant getSharedAt() { return sharedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedBy() { return revokedBy; }
    public String getRevokeReason() { return revokeReason; }
}
