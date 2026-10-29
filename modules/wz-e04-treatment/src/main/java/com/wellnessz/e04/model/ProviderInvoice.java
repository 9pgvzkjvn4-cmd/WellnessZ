package com.wellnessz.e04.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Provider invoice matched against the approved pre-authorisation (E04-F04). */
@Entity(name = "TxProviderInvoice")
@Table(name = "invoice", schema = "treatment")
public class ProviderInvoice {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "invoice_number")
    private String invoiceNumber;
    @Column(name = "amount")
    private BigDecimal amount;
    @Column(name = "currency")
    private String currency;
    @Column(name = "service_date")
    private LocalDate serviceDate;
    @Column(name = "referral_id")
    private UUID referralId;
    @Column(name = "preauth_id")
    private UUID preauthId;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "status")
    private String status;
    @Column(name = "matched_preauth_id")
    private UUID matchedPreauthId;
    @Column(name = "variance")
    private BigDecimal variance;
    @Column(name = "note")
    private String note;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ProviderInvoice() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String v) { this.invoiceNumber = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDate getServiceDate() { return serviceDate; }
    public void setServiceDate(LocalDate v) { this.serviceDate = v; }
    public UUID getReferralId() { return referralId; }
    public void setReferralId(UUID v) { this.referralId = v; }
    public UUID getPreauthId() { return preauthId; }
    public void setPreauthId(UUID v) { this.preauthId = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getMatchedPreauthId() { return matchedPreauthId; }
    public void setMatchedPreauthId(UUID v) { this.matchedPreauthId = v; }
    public BigDecimal getVariance() { return variance; }
    public void setVariance(BigDecimal v) { this.variance = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
