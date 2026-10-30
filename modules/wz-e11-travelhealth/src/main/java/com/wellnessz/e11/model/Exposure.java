package com.wellnessz.e11.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Occupational exposure (E11-F03) on a kernel EXPOSURE case. Details are encrypted. */
@Entity(name = "E11Exposure")
@Table(name = "exposure", schema = "travelhealth")
public class Exposure {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "exp_ref")
    private String expRef;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "pathway")
    private String pathway;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "details", columnDefinition = "text")
    private String details;
    @Column(name = "chemical_code")
    private String chemicalCode;
    @Column(name = "flight_ref")
    private String flightRef;
    @Column(name = "source_known")
    private Boolean sourceKnown;
    @Column(name = "incident_id")
    private UUID incidentId;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    /** New instance with a random id. */
    public static Exposure create() {
        Exposure e = new Exposure();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getExpRef() {
        return expRef;
    }

    public void setExpRef(String v) {
        this.expRef = v;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID v) {
        this.caseId = v;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public void setCaseRef(String v) {
        this.caseRef = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getPathway() {
        return pathway;
    }

    public void setPathway(String v) {
        this.pathway = v;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant v) {
        this.occurredAt = v;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String v) {
        this.details = v;
    }

    public String getChemicalCode() {
        return chemicalCode;
    }

    public void setChemicalCode(String v) {
        this.chemicalCode = v;
    }

    public String getFlightRef() {
        return flightRef;
    }

    public void setFlightRef(String v) {
        this.flightRef = v;
    }

    public Boolean getSourceKnown() {
        return sourceKnown;
    }

    public void setSourceKnown(Boolean v) {
        this.sourceKnown = v;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(UUID v) {
        this.incidentId = v;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String v) {
        this.createdBy = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }

    public long getVersion() {
        return version;
    }
}
