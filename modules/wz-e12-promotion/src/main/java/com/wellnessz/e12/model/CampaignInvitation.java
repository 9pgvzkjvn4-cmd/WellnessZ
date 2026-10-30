package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Invitation of an employee of a targeted unit to a published campaign. */
@Entity(name = "E12CampaignInvitation")
@Table(name = "campaign_invitation", schema = "promotion")
public class CampaignInvitation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "invited_at")
    private Instant invitedAt;

    public CampaignInvitation() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant v) { this.invitedAt = v; }
}
