package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Vaccine dose given by OH (mirrored into the E03 immunisation record). */
@Entity
@Table(name = "vaccine_administration", schema = "surveillance")
public class VaccineAdministration {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "vaccine_code")
    private String vaccineCode;
    @Column(name = "dose_number")
    private int doseNumber;
    @Column(name = "lot_number")
    private String lotNumber;
    @Column(name = "lot_expiry")
    private LocalDate lotExpiry;
    @Column(name = "body_site")
    private String bodySite;
    @Column(name = "route")
    private String route;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "administered_at")
    private Instant administeredAt;
    @Column(name = "consent_confirmed")
    private boolean consentConfirmed;
    @Column(name = "record_id")
    private UUID recordId;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public VaccineAdministration() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getVaccineCode() { return vaccineCode; }
    public void setVaccineCode(String v) { this.vaccineCode = v; }
    public int getDoseNumber() { return doseNumber; }
    public void setDoseNumber(int v) { this.doseNumber = v; }
    public String getLotNumber() { return lotNumber; }
    public void setLotNumber(String v) { this.lotNumber = v; }
    public LocalDate getLotExpiry() { return lotExpiry; }
    public void setLotExpiry(LocalDate v) { this.lotExpiry = v; }
    public String getBodySite() { return bodySite; }
    public void setBodySite(String v) { this.bodySite = v; }
    public String getRoute() { return route; }
    public void setRoute(String v) { this.route = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public Instant getAdministeredAt() { return administeredAt; }
    public void setAdministeredAt(Instant v) { this.administeredAt = v; }
    public boolean isConsentConfirmed() { return consentConfirmed; }
    public void setConsentConfirmed(boolean v) { this.consentConfirmed = v; }
    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID v) { this.recordId = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
