package com.wellnessz.e04.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Expected-recovery overrun of an active treatment plan (nightly treatment-variance job). */
@Entity(name = "TxVarianceAlert")
@Table(name = "variance_alert", schema = "treatment")
public class VarianceAlert {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "plan_id")
    private UUID planId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "expected_days")
    private int expectedDays;
    @Column(name = "actual_days")
    private int actualDays;
    @Column(name = "pct_over")
    private BigDecimal pctOver;
    @Column(name = "status")
    private String status;
    @Column(name = "raised_at")
    private Instant raisedAt;
    @Column(name = "acted_by")
    private String actedBy;
    @Column(name = "acted_at")
    private Instant actedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;

    public VarianceAlert() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID v) { this.planId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public int getExpectedDays() { return expectedDays; }
    public void setExpectedDays(int v) { this.expectedDays = v; }
    public int getActualDays() { return actualDays; }
    public void setActualDays(int v) { this.actualDays = v; }
    public BigDecimal getPctOver() { return pctOver; }
    public void setPctOver(BigDecimal v) { this.pctOver = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getRaisedAt() { return raisedAt; }
    public void setRaisedAt(Instant v) { this.raisedAt = v; }
    public String getActedBy() { return actedBy; }
    public void setActedBy(String v) { this.actedBy = v; }
    public Instant getActedAt() { return actedAt; }
    public void setActedAt(Instant v) { this.actedAt = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
