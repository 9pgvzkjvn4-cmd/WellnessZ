package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Medical (fit-note) certificate covering an absence (E06-F03). */
@Entity(name = "AbsenceCertificate")
@Table(name = "certificate", schema = "absence")
public class Certificate {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "episode_id")
    private UUID episodeId;
    @Column(name = "source")
    private String source;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "from_date")
    private LocalDate fromDate;
    @Column(name = "to_date")
    private LocalDate toDate;
    @Column(name = "issuer_name")
    private String issuerName;
    @Column(name = "issuer_registration")
    private String issuerRegistration;
    @Column(name = "certificate_number")
    private String certificateNumber;
    @Column(name = "partner_org")
    private String partnerOrg;
    @Column(name = "issue_date")
    private LocalDate issueDate;
    @Column(name = "fit_note")
    private String fitNote;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "extracted", columnDefinition = "text")
    private String extracted;
    @Column(name = "checklist")
    private String checklist;
    @Column(name = "status")
    private String status;
    @Column(name = "duplicate_of_id")
    private UUID duplicateOfId;
    @Column(name = "alteration_flags")
    private String alterationFlags;
    @Column(name = "sha256")
    private String sha256;
    @Column(name = "received_at")
    private Instant receivedAt;
    @Column(name = "received_by")
    private String receivedBy;
    @Column(name = "validated_by")
    private String validatedBy;
    @Column(name = "validated_at")
    private Instant validatedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "validation_note", columnDefinition = "text")
    private String validationNote;
    @Column(name = "verified_by")
    private String verifiedBy;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(name = "escalated")
    private boolean escalated;

    public Certificate() {
        // JPA; populated through setters by the owning service
    }

    public static Certificate create() {
        Certificate x = new Certificate();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getEpisodeId() { return episodeId; }
    public void setEpisodeId(UUID v) { this.episodeId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate v) { this.fromDate = v; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate v) { this.toDate = v; }
    public String getIssuerName() { return issuerName; }
    public void setIssuerName(String v) { this.issuerName = v; }
    public String getIssuerRegistration() { return issuerRegistration; }
    public void setIssuerRegistration(String v) { this.issuerRegistration = v; }
    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String v) { this.certificateNumber = v; }
    public String getPartnerOrg() { return partnerOrg; }
    public void setPartnerOrg(String v) { this.partnerOrg = v; }
    public LocalDate getIssueDate() { return issueDate; }
    public void setIssueDate(LocalDate v) { this.issueDate = v; }
    public String getFitNote() { return fitNote; }
    public void setFitNote(String v) { this.fitNote = v; }
    public String getExtracted() { return extracted; }
    public void setExtracted(String v) { this.extracted = v; }
    public String getChecklist() { return checklist; }
    public void setChecklist(String v) { this.checklist = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getDuplicateOfId() { return duplicateOfId; }
    public void setDuplicateOfId(UUID v) { this.duplicateOfId = v; }
    public String getAlterationFlags() { return alterationFlags; }
    public void setAlterationFlags(String v) { this.alterationFlags = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String v) { this.receivedBy = v; }
    public String getValidatedBy() { return validatedBy; }
    public void setValidatedBy(String v) { this.validatedBy = v; }
    public Instant getValidatedAt() { return validatedAt; }
    public void setValidatedAt(Instant v) { this.validatedAt = v; }
    public String getValidationNote() { return validationNote; }
    public void setValidationNote(String v) { this.validationNote = v; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String v) { this.verifiedBy = v; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant v) { this.verifiedAt = v; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean v) { this.escalated = v; }
}
