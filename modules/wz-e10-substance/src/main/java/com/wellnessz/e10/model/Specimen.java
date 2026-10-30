package com.wellnessz.e10.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** A collected specimen (primary or split) with its electronic chain-of-custody form. */
@Entity(name = "E10Specimen")
@Table(name = "specimen", schema = "substance")
public class Specimen {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "specimen_id")
    private String specimenId;
    @Column(name = "test_id")
    private UUID testId;
    @Column(name = "specimen_type")
    private String specimenType;
    @Column(name = "kind")
    private String kind;
    @Column(name = "parent_specimen_id")
    private String parentSpecimenId;
    @Column(name = "collected_at")
    private Instant collectedAt;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "temperature_in_range")
    private Boolean temperatureInRange;
    @Column(name = "seal_intact")
    private boolean sealIntact;
    @Column(name = "donor_identity_checked")
    private boolean donorIdentityChecked;
    @Column(name = "split_specimen")
    private boolean splitSpecimen;
    @Column(name = "collector")
    private String collector;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "remarks", columnDefinition = "text")
    private String remarks;
    @Column(name = "lab_code")
    private String labCode;
    @Column(name = "out_of_window")
    private boolean outOfWindow;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "lab_received_at")
    private Instant labReceivedAt;
    @Column(name = "resulted_at")
    private Instant resultedAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public Specimen() {
        // JPA; populated through setters by the owning service
    }

    public static Specimen create() {
        Specimen x = new Specimen();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSpecimenId() { return specimenId; }
    public void setSpecimenId(String v) { this.specimenId = v; }
    public UUID getTestId() { return testId; }
    public void setTestId(UUID v) { this.testId = v; }
    public String getSpecimenType() { return specimenType; }
    public void setSpecimenType(String v) { this.specimenType = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getParentSpecimenId() { return parentSpecimenId; }
    public void setParentSpecimenId(String v) { this.parentSpecimenId = v; }
    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant v) { this.collectedAt = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public Boolean getTemperatureInRange() { return temperatureInRange; }
    public void setTemperatureInRange(Boolean v) { this.temperatureInRange = v; }
    public boolean isSealIntact() { return sealIntact; }
    public void setSealIntact(boolean v) { this.sealIntact = v; }
    public boolean isDonorIdentityChecked() { return donorIdentityChecked; }
    public void setDonorIdentityChecked(boolean v) { this.donorIdentityChecked = v; }
    public boolean isSplitSpecimen() { return splitSpecimen; }
    public void setSplitSpecimen(boolean v) { this.splitSpecimen = v; }
    public String getCollector() { return collector; }
    public void setCollector(String v) { this.collector = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public String getLabCode() { return labCode; }
    public void setLabCode(String v) { this.labCode = v; }
    public boolean isOutOfWindow() { return outOfWindow; }
    public void setOutOfWindow(boolean v) { this.outOfWindow = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public Instant getLabReceivedAt() { return labReceivedAt; }
    public void setLabReceivedAt(Instant v) { this.labReceivedAt = v; }
    public Instant getResultedAt() { return resultedAt; }
    public void setResultedAt(Instant v) { this.resultedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
