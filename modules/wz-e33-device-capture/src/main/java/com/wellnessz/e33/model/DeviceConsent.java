package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Granular wearable-data scopes on top of a platform {@code WEARABLE_DATA} consent (E33-F12). */
@Entity(name = "E33DeviceConsent")
@Table(name = "device_consent", schema = "devicecap")
public class DeviceConsent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "purpose")
    private String purpose;
    @Column(name = "scopes")
    private String scopes;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "status")
    private String status;
    @Column(name = "granted_at")
    private Instant grantedAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Version
    @Column(name = "version")
    private Long version;

    protected DeviceConsent() {
        // JPA
    }

    public DeviceConsent(String employeeId, UUID consentId, String purpose, String scopes, UUID campaignId, Instant grantedAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.consentId = consentId;
        this.purpose = purpose;
        this.scopes = scopes;
        this.campaignId = campaignId;
        this.status = "ACTIVE";
        this.grantedAt = grantedAt;
    }

    public void revoke(Instant at) {
        this.status = "REVOKED";
        this.revokedAt = at;
    }

    public UUID getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public UUID getConsentId() { return consentId; }
    public String getPurpose() { return purpose; }
    public String getScopes() { return scopes; }
    public UUID getCampaignId() { return campaignId; }
    public String getStatus() { return status; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
