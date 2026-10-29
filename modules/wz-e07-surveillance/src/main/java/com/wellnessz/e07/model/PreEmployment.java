package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Pre-employment / pre-placement assessment of a candidate (not an HRIS employee). */
@Entity
@Table(name = "pre_employment", schema = "surveillance")
public class PreEmployment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "offer_ref")
    private String offerRef;
    @Column(name = "candidate_id")
    private String candidateId;
    @Column(name = "candidate_name")
    private String candidateName;
    @Column(name = "email")
    private String email;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "subsidiary_code")
    private String subsidiaryCode;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "location_code")
    private String locationCode;
    @Column(name = "proposed_start")
    private LocalDate proposedStart;
    @Column(name = "panel")
    private String panel;
    @Column(name = "status")
    private String status;
    @Column(name = "hr_clearance")
    private String hrClearance;
    @Column(name = "outcome")
    private String outcome;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "conditions", columnDefinition = "text")
    private String conditions;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "questionnaire", columnDefinition = "text")
    private String questionnaire;
    @Column(name = "questionnaire_completed_at")
    private Instant questionnaireCompletedAt;
    @Column(name = "consent_given")
    private boolean consentGiven;
    @Column(name = "consent_purposes")
    private String consentPurposes;
    @Column(name = "consent_at")
    private Instant consentAt;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "appointment_starts_at")
    private Instant appointmentStartsAt;
    @Column(name = "appointment_site")
    private String appointmentSite;
    @Column(name = "gov_type")
    private String govType;
    @Column(name = "gov_reference")
    private String govReference;
    @Column(name = "gov_status")
    private String govStatus;
    @Column(name = "gov_expires_on")
    private LocalDate govExpiresOn;
    @Column(name = "documents", columnDefinition = "text")
    private String documents;
    @Column(name = "outcome_document_id")
    private UUID outcomeDocumentId;
    @Column(name = "outcome_by")
    private String outcomeBy;
    @Column(name = "outcome_at")
    private Instant outcomeAt;
    @Column(name = "hr_message_id")
    private String hrMessageId;
    @Column(name = "sla_due_at")
    private Instant slaDueAt;
    @Column(name = "retention_until")
    private LocalDate retentionUntil;
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
    @Column(name = "purged_at")
    private Instant purgedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    public PreEmployment() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getOfferRef() { return offerRef; }
    public void setOfferRef(String v) { this.offerRef = v; }
    public String getCandidateId() { return candidateId; }
    public void setCandidateId(String v) { this.candidateId = v; }
    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String v) { this.candidateName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getJobCode() { return jobCode; }
    public void setJobCode(String v) { this.jobCode = v; }
    public String getSubsidiaryCode() { return subsidiaryCode; }
    public void setSubsidiaryCode(String v) { this.subsidiaryCode = v; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String v) { this.departmentCode = v; }
    public String getLocationCode() { return locationCode; }
    public void setLocationCode(String v) { this.locationCode = v; }
    public LocalDate getProposedStart() { return proposedStart; }
    public void setProposedStart(LocalDate v) { this.proposedStart = v; }
    public String getPanel() { return panel; }
    public void setPanel(String v) { this.panel = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getHrClearance() { return hrClearance; }
    public void setHrClearance(String v) { this.hrClearance = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getConditions() { return conditions; }
    public void setConditions(String v) { this.conditions = v; }
    public String getQuestionnaire() { return questionnaire; }
    public void setQuestionnaire(String v) { this.questionnaire = v; }
    public Instant getQuestionnaireCompletedAt() { return questionnaireCompletedAt; }
    public void setQuestionnaireCompletedAt(Instant v) { this.questionnaireCompletedAt = v; }
    public boolean isConsentGiven() { return consentGiven; }
    public void setConsentGiven(boolean v) { this.consentGiven = v; }
    public String getConsentPurposes() { return consentPurposes; }
    public void setConsentPurposes(String v) { this.consentPurposes = v; }
    public Instant getConsentAt() { return consentAt; }
    public void setConsentAt(Instant v) { this.consentAt = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
    public Instant getAppointmentStartsAt() { return appointmentStartsAt; }
    public void setAppointmentStartsAt(Instant v) { this.appointmentStartsAt = v; }
    public String getAppointmentSite() { return appointmentSite; }
    public void setAppointmentSite(String v) { this.appointmentSite = v; }
    public String getGovType() { return govType; }
    public void setGovType(String v) { this.govType = v; }
    public String getGovReference() { return govReference; }
    public void setGovReference(String v) { this.govReference = v; }
    public String getGovStatus() { return govStatus; }
    public void setGovStatus(String v) { this.govStatus = v; }
    public LocalDate getGovExpiresOn() { return govExpiresOn; }
    public void setGovExpiresOn(LocalDate v) { this.govExpiresOn = v; }
    public String getDocuments() { return documents; }
    public void setDocuments(String v) { this.documents = v; }
    public UUID getOutcomeDocumentId() { return outcomeDocumentId; }
    public void setOutcomeDocumentId(UUID v) { this.outcomeDocumentId = v; }
    public String getOutcomeBy() { return outcomeBy; }
    public void setOutcomeBy(String v) { this.outcomeBy = v; }
    public Instant getOutcomeAt() { return outcomeAt; }
    public void setOutcomeAt(Instant v) { this.outcomeAt = v; }
    public String getHrMessageId() { return hrMessageId; }
    public void setHrMessageId(String v) { this.hrMessageId = v; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant v) { this.slaDueAt = v; }
    public LocalDate getRetentionUntil() { return retentionUntil; }
    public void setRetentionUntil(LocalDate v) { this.retentionUntil = v; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
    public Instant getPurgedAt() { return purgedAt; }
    public void setPurgedAt(Instant v) { this.purgedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
}
