package com.wellnessz.e08.model;

import java.math.BigDecimal;
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

/** Claim record (E08-F01): one per case and claim type. Notes are encrypted; reserves / subrogation are JSON. */
@Entity(name = "E08Claim")
@Table(name = "claim", schema = "claims")
public class Claim {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_ref")
    private String claimRef;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "claim_type")
    private String claimType;
    @Column(name = "status")
    private String status;
    @Column(name = "payer_code")
    private String payerCode;
    @Column(name = "policy_number")
    private String policyNumber;
    @Column(name = "adjuster_name")
    private String adjusterName;
    @Column(name = "adjuster_email")
    private String adjusterEmail;
    @Column(name = "adjuster_username")
    private String adjusterUsername;
    @Column(name = "lost_time")
    private boolean lostTime;
    @Column(name = "lost_time_at")
    private Instant lostTimeAt;
    @Column(name = "incident_date")
    private LocalDate incidentDate;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes", columnDefinition = "text")
    private String notes;
    @Column(name = "notified_at")
    private Instant notifiedAt;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "settled_at")
    private Instant settledAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "nurse_username")
    private String nurseUsername;
    @Column(name = "nurse_assigned_at")
    private Instant nurseAssignedAt;
    @Column(name = "reserves", columnDefinition = "text")
    private String reserves;
    @Column(name = "paid_total")
    private BigDecimal paidTotal;
    @Column(name = "paid_currency")
    private String paidCurrency;
    @Column(name = "subrogation", columnDefinition = "text")
    private String subrogation;
    @Column(name = "litigation_hold")
    private boolean litigationHold;
    @Column(name = "hold_reason")
    private String holdReason;
    @Column(name = "hold_set_by")
    private String holdSetBy;
    @Column(name = "hold_set_at")
    private Instant holdSetAt;
    @Column(name = "privileged")
    private boolean privileged;
    @Column(name = "privilege_basis")
    private String privilegeBasis;
    @Column(name = "privilege_set_by")
    private String privilegeSetBy;
    @Column(name = "privilege_set_at")
    private Instant privilegeSetAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public Claim() {
        // JPA; populated through setters by the owning service
    }

    public static Claim create() {
        Claim x = new Claim();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getClaimRef() { return claimRef; }
    public void setClaimRef(String v) { this.claimRef = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getClaimType() { return claimType; }
    public void setClaimType(String v) { this.claimType = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getPayerCode() { return payerCode; }
    public void setPayerCode(String v) { this.payerCode = v; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String v) { this.policyNumber = v; }
    public String getAdjusterName() { return adjusterName; }
    public void setAdjusterName(String v) { this.adjusterName = v; }
    public String getAdjusterEmail() { return adjusterEmail; }
    public void setAdjusterEmail(String v) { this.adjusterEmail = v; }
    public String getAdjusterUsername() { return adjusterUsername; }
    public void setAdjusterUsername(String v) { this.adjusterUsername = v; }
    public boolean isLostTime() { return lostTime; }
    public void setLostTime(boolean v) { this.lostTime = v; }
    public Instant getLostTimeAt() { return lostTimeAt; }
    public void setLostTimeAt(Instant v) { this.lostTimeAt = v; }
    public LocalDate getIncidentDate() { return incidentDate; }
    public void setIncidentDate(LocalDate v) { this.incidentDate = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant v) { this.notifiedAt = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant v) { this.settledAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getNurseUsername() { return nurseUsername; }
    public void setNurseUsername(String v) { this.nurseUsername = v; }
    public Instant getNurseAssignedAt() { return nurseAssignedAt; }
    public void setNurseAssignedAt(Instant v) { this.nurseAssignedAt = v; }
    public String getReserves() { return reserves; }
    public void setReserves(String v) { this.reserves = v; }
    public BigDecimal getPaidTotal() { return paidTotal; }
    public void setPaidTotal(BigDecimal v) { this.paidTotal = v; }
    public String getPaidCurrency() { return paidCurrency; }
    public void setPaidCurrency(String v) { this.paidCurrency = v; }
    public String getSubrogation() { return subrogation; }
    public void setSubrogation(String v) { this.subrogation = v; }
    public boolean isLitigationHold() { return litigationHold; }
    public void setLitigationHold(boolean v) { this.litigationHold = v; }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String v) { this.holdReason = v; }
    public String getHoldSetBy() { return holdSetBy; }
    public void setHoldSetBy(String v) { this.holdSetBy = v; }
    public Instant getHoldSetAt() { return holdSetAt; }
    public void setHoldSetAt(Instant v) { this.holdSetAt = v; }
    public boolean isPrivileged() { return privileged; }
    public void setPrivileged(boolean v) { this.privileged = v; }
    public String getPrivilegeBasis() { return privilegeBasis; }
    public void setPrivilegeBasis(String v) { this.privilegeBasis = v; }
    public String getPrivilegeSetBy() { return privilegeSetBy; }
    public void setPrivilegeSetBy(String v) { this.privilegeSetBy = v; }
    public Instant getPrivilegeSetAt() { return privilegeSetAt; }
    public void setPrivilegeSetAt(Instant v) { this.privilegeSetAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
