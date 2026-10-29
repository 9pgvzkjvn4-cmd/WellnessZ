package com.wellnessz.e05.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A work restriction in force for an employee, with validity and expiry tracking (E05-F06). */
@Entity
@Table(name = "restriction_instance", schema = "fitness")
public class RestrictionInstance {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "decision_id")
    private UUID decisionId;
    @Column(name = "code")
    private String code;
    @Column(name = "label")
    private String label;
    @Column(name = "restriction_value")
    private BigDecimal restrictionValue;
    @Column(name = "valid_from")
    private LocalDate validFrom;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "status")
    private String status;
    @Column(name = "alerted_at")
    private Instant alertedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private String closedBy;
    @Column(name = "close_reason")
    private String closeReason;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public RestrictionInstance() {
        // JPA; populated through setters by the owning service
    }

    public static RestrictionInstance create() {
        RestrictionInstance x = new RestrictionInstance();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String v) { this.departmentCode = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public UUID getDecisionId() { return decisionId; }
    public void setDecisionId(UUID v) { this.decisionId = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public BigDecimal getRestrictionValue() { return restrictionValue; }
    public void setRestrictionValue(BigDecimal v) { this.restrictionValue = v; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate v) { this.validFrom = v; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate v) { this.validUntil = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getAlertedAt() { return alertedAt; }
    public void setAlertedAt(Instant v) { this.alertedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String v) { this.closedBy = v; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String v) { this.closeReason = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
