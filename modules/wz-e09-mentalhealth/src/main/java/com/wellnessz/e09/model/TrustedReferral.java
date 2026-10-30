package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Referral of a pseudonymous peer contact to the trusted mental-health professional. */
@Entity(name = "E09TrustedReferral")
@Table(name = "trusted_referral", schema = "mentalhealth")
public class TrustedReferral {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "peer_contact_id")
    private UUID peerContactId;
    @Column(name = "contact_code")
    private String contactCode;
    @Column(name = "referred_by")
    private String referredBy;
    @Column(name = "referred_at")
    private Instant referredAt;
    @Column(name = "status")
    private String status;
    @Column(name = "picked_up_by")
    private String pickedUpBy;
    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    public TrustedReferral() {
        // JPA; populated through setters by the owning service
    }

    public static TrustedReferral create() {
        TrustedReferral x = new TrustedReferral();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getPeerContactId() { return peerContactId; }
    public void setPeerContactId(UUID v) { this.peerContactId = v; }
    public String getContactCode() { return contactCode; }
    public void setContactCode(String v) { this.contactCode = v; }
    public String getReferredBy() { return referredBy; }
    public void setReferredBy(String v) { this.referredBy = v; }
    public Instant getReferredAt() { return referredAt; }
    public void setReferredAt(Instant v) { this.referredAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPickedUpBy() { return pickedUpBy; }
    public void setPickedUpBy(String v) { this.pickedUpBy = v; }
    public Instant getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(Instant v) { this.pickedUpAt = v; }
}
