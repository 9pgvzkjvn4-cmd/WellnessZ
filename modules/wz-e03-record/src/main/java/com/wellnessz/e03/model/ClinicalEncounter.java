package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Clinical encounter documented with a versioned template; immutable once signed. */
@Entity
@Table(name = "encounter", schema = "ohrecord")
public class ClinicalEncounter {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "type")
    private String type;
    @Column(name = "template_version")
    private int templateVersion;
    @Column(name = "status")
    private String status;
    @Column(name = "classification")
    private String classification;
    @Column(name = "title")
    private String title;
    @Column(name = "author")
    private String author;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "answers", columnDefinition = "text")
    private String answers;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "soap", columnDefinition = "text")
    private String soap;
    @Column(name = "fitness_standard_code")
    private String fitnessStandardCode;
    @Column(name = "primary_diagnosis_system")
    private String primaryDiagnosisSystem;
    @Column(name = "primary_diagnosis_code")
    private String primaryDiagnosisCode;
    @Column(name = "primary_diagnosis_display")
    private String primaryDiagnosisDisplay;
    @Column(name = "ai_assisted")
    private boolean aiAssisted;
    @Column(name = "ai_consent_id")
    private UUID aiConsentId;
    @Column(name = "signed_by")
    private String signedBy;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "signature_sha256")
    private String signatureSha256;
    @Column(name = "eie_by")
    private String eieBy;
    @Column(name = "eie_at")
    private Instant eieAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "eie_reason", columnDefinition = "text")
    private String eieReason;

    public ClinicalEncounter() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getTemplateVersion() {
        return templateVersion;
    }

    public void setTemplateVersion(int templateVersion) {
        this.templateVersion = templateVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getAnswers() {
        return answers;
    }

    public void setAnswers(String answers) {
        this.answers = answers;
    }

    public String getSoap() {
        return soap;
    }

    public void setSoap(String soap) {
        this.soap = soap;
    }

    public String getFitnessStandardCode() {
        return fitnessStandardCode;
    }

    public void setFitnessStandardCode(String fitnessStandardCode) {
        this.fitnessStandardCode = fitnessStandardCode;
    }

    public String getPrimaryDiagnosisSystem() {
        return primaryDiagnosisSystem;
    }

    public void setPrimaryDiagnosisSystem(String primaryDiagnosisSystem) {
        this.primaryDiagnosisSystem = primaryDiagnosisSystem;
    }

    public String getPrimaryDiagnosisCode() {
        return primaryDiagnosisCode;
    }

    public void setPrimaryDiagnosisCode(String primaryDiagnosisCode) {
        this.primaryDiagnosisCode = primaryDiagnosisCode;
    }

    public String getPrimaryDiagnosisDisplay() {
        return primaryDiagnosisDisplay;
    }

    public void setPrimaryDiagnosisDisplay(String primaryDiagnosisDisplay) {
        this.primaryDiagnosisDisplay = primaryDiagnosisDisplay;
    }

    public boolean isAiAssisted() {
        return aiAssisted;
    }

    public void setAiAssisted(boolean aiAssisted) {
        this.aiAssisted = aiAssisted;
    }

    public UUID getAiConsentId() {
        return aiConsentId;
    }

    public void setAiConsentId(UUID aiConsentId) {
        this.aiConsentId = aiConsentId;
    }

    public String getSignedBy() {
        return signedBy;
    }

    public void setSignedBy(String signedBy) {
        this.signedBy = signedBy;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public String getSignatureSha256() {
        return signatureSha256;
    }

    public void setSignatureSha256(String signatureSha256) {
        this.signatureSha256 = signatureSha256;
    }

    public String getEieBy() {
        return eieBy;
    }

    public void setEieBy(String eieBy) {
        this.eieBy = eieBy;
    }

    public Instant getEieAt() {
        return eieAt;
    }

    public void setEieAt(Instant eieAt) {
        this.eieAt = eieAt;
    }

    public String getEieReason() {
        return eieReason;
    }

    public void setEieReason(String eieReason) {
        this.eieReason = eieReason;
    }
}
