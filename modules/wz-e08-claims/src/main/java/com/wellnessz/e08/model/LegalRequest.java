package com.wellnessz.e08.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Medico-legal / expert / IME request from legal counsel (E08-F06). Questions are encrypted. */
@Entity(name = "E08LegalRequest")
@Table(name = "legal_request", schema = "claims")
public class LegalRequest {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_ref")
    private String requestRef;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "request_type")
    private String requestType;
    @Column(name = "status")
    private String status;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "questions", columnDefinition = "text")
    private String questions;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "referral_id")
    private UUID referralId;
    @Column(name = "referral_ref")
    private String referralRef;
    @Column(name = "referral_status")
    private String referralStatus;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private String closedBy;

    public LegalRequest() {
        // JPA; populated through setters by the owning service
    }

    public static LegalRequest create() {
        LegalRequest x = new LegalRequest();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRequestRef() { return requestRef; }
    public void setRequestRef(String v) { this.requestRef = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getRequestType() { return requestType; }
    public void setRequestType(String v) { this.requestType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getQuestions() { return questions; }
    public void setQuestions(String v) { this.questions = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public UUID getReferralId() { return referralId; }
    public void setReferralId(UUID v) { this.referralId = v; }
    public String getReferralRef() { return referralRef; }
    public void setReferralRef(String v) { this.referralRef = v; }
    public String getReferralStatus() { return referralStatus; }
    public void setReferralStatus(String v) { this.referralStatus = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String v) { this.closedBy = v; }
}
