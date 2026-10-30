package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Consented wearable programme enrolment (E12-BL-005). */
@Entity(name = "E12WearableEnrolment")
@Table(name = "wearable_enrolment", schema = "promotion")
public class WearableEnrolment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "device_type")
    private String deviceType;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "status")
    private String status;
    @Column(name = "enrolled_at")
    private Instant enrolledAt;
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
    @Column(name = "purged_rows")
    private Integer purgedRows;

    public WearableEnrolment() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String v) { this.deviceType = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getEnrolledAt() { return enrolledAt; }
    public void setEnrolledAt(Instant v) { this.enrolledAt = v; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
    public Integer getPurgedRows() { return purgedRows; }
    public void setPurgedRows(Integer v) { this.purgedRows = v; }
}
