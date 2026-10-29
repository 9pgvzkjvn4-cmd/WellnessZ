package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Status history of a referral. */
@Entity(name = "TxReferralHistory")
@Table(name = "referral_history", schema = "treatment")
public class ReferralHistory {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "referral_id")
    private UUID referralId;
    @Column(name = "status")
    private String status;
    @Column(name = "at")
    private Instant at;
    @Column(name = "by_actor")
    private String byActor;

    public ReferralHistory() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getReferralId() { return referralId; }
    public void setReferralId(UUID v) { this.referralId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getAt() { return at; }
    public void setAt(Instant v) { this.at = v; }
    public String getByActor() { return byActor; }
    public void setByActor(String v) { this.byActor = v; }
}
