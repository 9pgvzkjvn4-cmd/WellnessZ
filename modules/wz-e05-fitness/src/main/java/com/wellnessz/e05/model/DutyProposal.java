package com.wellnessz.e05.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A modified-duty proposal awaiting the line manager's decision (E05-BL-005/006). */
@Entity
@Table(name = "duty_proposal", schema = "fitness")
public class DutyProposal {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "job_title")
    private String jobTitle;
    @Column(name = "duties")
    private String duties;
    @Column(name = "restrictions", columnDefinition = "text")
    private String restrictions;
    @Column(name = "hours_per_day")
    private BigDecimal hoursPerDay;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "status")
    private String status;
    @Column(name = "proposed_by")
    private String proposedBy;
    @Column(name = "proposed_at")
    private Instant proposedAt;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "reason_code")
    private String reasonCode;
    @Column(name = "note")
    private String note;

    public DutyProposal() {
        // JPA; populated through setters by the owning service
    }

    public static DutyProposal create() {
        DutyProposal x = new DutyProposal();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getJobCode() { return jobCode; }
    public void setJobCode(String v) { this.jobCode = v; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String v) { this.jobTitle = v; }
    public String getDuties() { return duties; }
    public void setDuties(String v) { this.duties = v; }
    public String getRestrictions() { return restrictions; }
    public void setRestrictions(String v) { this.restrictions = v; }
    public BigDecimal getHoursPerDay() { return hoursPerDay; }
    public void setHoursPerDay(BigDecimal v) { this.hoursPerDay = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getProposedBy() { return proposedBy; }
    public void setProposedBy(String v) { this.proposedBy = v; }
    public Instant getProposedAt() { return proposedAt; }
    public void setProposedAt(Instant v) { this.proposedAt = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getReasonCode() { return reasonCode; }
    public void setReasonCode(String v) { this.reasonCode = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
}
