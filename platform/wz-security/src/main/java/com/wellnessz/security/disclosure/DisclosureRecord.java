package com.wellnessz.security.disclosure;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One disclosure of an employee's information to a party outside the OH team (E03-BL-022). Metadata only. */
@Entity
@Table(name = "disclosure_log", schema = "platform")
public class DisclosureRecord {

    @Id
    private UUID id;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "recipient_type", nullable = false, length = 20)
    private String recipientType;
    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;
    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;
    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;
    @Column(name = "resource_title", nullable = false, length = 300)
    private String resourceTitle;
    @Column(nullable = false, length = 60)
    private String purpose;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "disclosed_by", nullable = false, length = 200)
    private String disclosedBy;
    @Column(name = "disclosed_at", nullable = false)
    private Instant disclosedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    protected DisclosureRecord() {
    }

    public DisclosureRecord(String employeeId, String recipientType, String recipientName, String resourceType, String resourceId,
                            String resourceTitle, String purpose, UUID consentId, String disclosedBy, Instant at) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.recipientType = recipientType;
        this.recipientName = recipientName;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.resourceTitle = resourceTitle;
        this.purpose = purpose;
        this.consentId = consentId;
        this.disclosedBy = disclosedBy;
        this.disclosedAt = at;
    }

    public void revoke(String reason, Instant at) {
        if (revokedAt == null) {
            this.revokedAt = at;
            this.revokedReason = reason;
        }
    }

    public UUID getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public String getRecipientType() { return recipientType; }
    public String getRecipientName() { return recipientName; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getResourceTitle() { return resourceTitle; }
    public String getPurpose() { return purpose; }
    public UUID getConsentId() { return consentId; }
    public String getDisclosedBy() { return disclosedBy; }
    public Instant getDisclosedAt() { return disclosedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public String getRevokedReason() { return revokedReason; }
}
