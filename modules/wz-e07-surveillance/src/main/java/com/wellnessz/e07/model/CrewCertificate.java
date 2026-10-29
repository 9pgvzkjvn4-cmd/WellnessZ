package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Crew medical certificate (internal AME examination or external AME / AeMC import). */
@Entity
@Table(name = "crew_certificate", schema = "surveillance")
public class CrewCertificate {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "certificate_type")
    private String certificateType;
    @Column(name = "certificate_number")
    private String certificateNumber;
    @Column(name = "issuing_authority")
    private String issuingAuthority;
    @Column(name = "examined_on")
    private LocalDate examinedOn;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "limitation_codes")
    private String limitationCodes;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "suspended")
    private boolean suspended;
    @Column(name = "source")
    private String source;
    @Column(name = "aemc_name")
    private String aemcName;
    @Column(name = "ame_name")
    private String ameName;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "answers", columnDefinition = "text")
    private String answers;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "alerts_sent")
    private String alertsSent;
    @Column(name = "expired_published")
    private boolean expiredPublished;
    @Column(name = "superseded")
    private boolean superseded;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public CrewCertificate() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getCertificateType() { return certificateType; }
    public void setCertificateType(String v) { this.certificateType = v; }
    public String getCertificateNumber() { return certificateNumber; }
    public void setCertificateNumber(String v) { this.certificateNumber = v; }
    public String getIssuingAuthority() { return issuingAuthority; }
    public void setIssuingAuthority(String v) { this.issuingAuthority = v; }
    public LocalDate getExaminedOn() { return examinedOn; }
    public void setExaminedOn(LocalDate v) { this.examinedOn = v; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate v) { this.validUntil = v; }
    public String getLimitationCodes() { return limitationCodes; }
    public void setLimitationCodes(String v) { this.limitationCodes = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean v) { this.suspended = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getAemcName() { return aemcName; }
    public void setAemcName(String v) { this.aemcName = v; }
    public String getAmeName() { return ameName; }
    public void setAmeName(String v) { this.ameName = v; }
    public String getAnswers() { return answers; }
    public void setAnswers(String v) { this.answers = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getAlertsSent() { return alertsSent; }
    public void setAlertsSent(String v) { this.alertsSent = v; }
    public boolean isExpiredPublished() { return expiredPublished; }
    public void setExpiredPublished(boolean v) { this.expiredPublished = v; }
    public boolean isSuperseded() { return superseded; }
    public void setSuperseded(boolean v) { this.superseded = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
