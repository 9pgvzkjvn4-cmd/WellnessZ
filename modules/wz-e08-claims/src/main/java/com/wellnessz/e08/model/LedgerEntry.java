package com.wellnessz.e08.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Cost ledger entry (E08-F05): claim-level, or case-level when the case has no claim yet. Unit snapshot for roll-ups. */
@Entity(name = "E08LedgerEntry")
@Table(name = "cost_entry", schema = "claims")
public class LedgerEntry {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "category")
    private String category;
    @Column(name = "kind")
    private String kind;
    @Column(name = "amount")
    private BigDecimal amount;
    @Column(name = "currency")
    private String currency;
    @Column(name = "incurred_on")
    private LocalDate incurredOn;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "description")
    private String description;
    @Column(name = "invoice_number")
    private String invoiceNumber;
    @Column(name = "source_type")
    private String sourceType;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "subsidiary_code")
    private String subsidiaryCode;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "cost_centre")
    private String costCentre;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public LedgerEntry() {
        // JPA; populated through setters by the owning service
    }

    public static LedgerEntry create() {
        LedgerEntry x = new LedgerEntry();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public LocalDate getIncurredOn() { return incurredOn; }
    public void setIncurredOn(LocalDate v) { this.incurredOn = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String v) { this.invoiceNumber = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getSubsidiaryCode() { return subsidiaryCode; }
    public void setSubsidiaryCode(String v) { this.subsidiaryCode = v; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String v) { this.departmentCode = v; }
    public String getCostCentre() { return costCentre; }
    public void setCostCentre(String v) { this.costCentre = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
