package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Calibration record of a piece of equipment. */
@Entity
@Table(name = "calibration", schema = "scheduling")
public class Calibration {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "equipment_id")
    private UUID equipmentId;
    @Column(name = "performed_on")
    private LocalDate performedOn;
    @Column(name = "vendor")
    private String vendor;
    @Column(name = "certificate_number")
    private String certificateNumber;
    @Column(name = "result")
    private String result;
    @Column(name = "next_due_on")
    private LocalDate nextDueOn;
    @Column(name = "document_sha256")
    private String documentSha256;
    @JdbcTypeCode(SqlTypes.VARBINARY) @Column(name = "document_content")
    private byte[] documentContent;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public Calibration() {
        // JPA; populated through setters by the owning service
    }

    public static Calibration create() {
        Calibration x = new Calibration();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEquipmentId() { return equipmentId; }
    public void setEquipmentId(UUID v) { this.equipmentId = v; }
    public LocalDate getPerformedOn() { return performedOn; }
    public void setPerformedOn(LocalDate v) { this.performedOn = v; }
    public String getVendor() { return vendor; }
    public void setVendor(String v) { this.vendor = v; }
    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String v) { this.certificateNumber = v; }
    public String getResult() { return result; }
    public void setResult(String v) { this.result = v; }
    public LocalDate getNextDueOn() { return nextDueOn; }
    public void setNextDueOn(LocalDate v) { this.nextDueOn = v; }
    public String getDocumentSha256() { return documentSha256; }
    public void setDocumentSha256(String v) { this.documentSha256 = v; }
    public byte[] getDocumentContent() { return documentContent; }
    public void setDocumentContent(byte[] v) { this.documentContent = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
