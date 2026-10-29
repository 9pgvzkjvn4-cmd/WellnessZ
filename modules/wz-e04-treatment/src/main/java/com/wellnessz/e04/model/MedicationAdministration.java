package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Clinic medication administration (mirrored into the E03 medication list as ADMINISTERED). */
@Entity(name = "TxMedicationAdministration")
@Table(name = "administration", schema = "treatment")
public class MedicationAdministration {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "item_code")
    private String itemCode;
    @Column(name = "item_name")
    private String itemName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "dose", columnDefinition = "text")
    private String dose;
    @Column(name = "route")
    private String route;
    @Column(name = "batch_number")
    private String batchNumber;
    @Column(name = "expiry")
    private LocalDate expiry;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "administered_by")
    private String administeredBy;
    @Column(name = "administered_at")
    private Instant administeredAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "allergy_override", columnDefinition = "text")
    private String allergyOverride;
    @Column(name = "medication_id")
    private UUID medicationId;

    public MedicationAdministration() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String v) { this.itemCode = v; }
    public String getItemName() { return itemName; }
    public void setItemName(String v) { this.itemName = v; }
    public String getDose() { return dose; }
    public void setDose(String v) { this.dose = v; }
    public String getRoute() { return route; }
    public void setRoute(String v) { this.route = v; }
    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String v) { this.batchNumber = v; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate v) { this.expiry = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getAdministeredBy() { return administeredBy; }
    public void setAdministeredBy(String v) { this.administeredBy = v; }
    public Instant getAdministeredAt() { return administeredAt; }
    public void setAdministeredAt(Instant v) { this.administeredAt = v; }
    public String getAllergyOverride() { return allergyOverride; }
    public void setAllergyOverride(String v) { this.allergyOverride = v; }
    public UUID getMedicationId() { return medicationId; }
    public void setMedicationId(UUID v) { this.medicationId = v; }
}
