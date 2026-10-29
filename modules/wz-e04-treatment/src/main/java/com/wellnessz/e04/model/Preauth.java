package com.wellnessz.e04.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Pre-authorisation / guarantee-of-payment request to an insurer or TPA (E04-F04). */
@Entity(name = "TxPreauth")
@Table(name = "preauth", schema = "treatment")
public class Preauth {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "payer_code")
    private String payerCode;
    @Column(name = "service_code")
    private String serviceCode;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "referral_id")
    private UUID referralId;
    @Column(name = "estimate_amount")
    private BigDecimal estimateAmount;
    @Column(name = "currency")
    private String currency;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "justification", columnDefinition = "text")
    private String justification;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "status")
    private String status;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "sla_due_at")
    private Instant slaDueAt;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "approval_ref")
    private String approvalRef;
    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "decision_note")
    private String decisionNote;

    public Preauth() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getPayerCode() { return payerCode; }
    public void setPayerCode(String v) { this.payerCode = v; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String v) { this.serviceCode = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public UUID getReferralId() { return referralId; }
    public void setReferralId(UUID v) { this.referralId = v; }
    public BigDecimal getEstimateAmount() { return estimateAmount; }
    public void setEstimateAmount(BigDecimal v) { this.estimateAmount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getJustification() { return justification; }
    public void setJustification(String v) { this.justification = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant v) { this.slaDueAt = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public String getApprovalRef() { return approvalRef; }
    public void setApprovalRef(String v) { this.approvalRef = v; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public void setApprovedAmount(BigDecimal v) { this.approvedAmount = v; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate v) { this.validUntil = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
}
