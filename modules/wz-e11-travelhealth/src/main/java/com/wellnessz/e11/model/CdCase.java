package com.wellnessz.e11.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Communicable-disease case (E11-F02) linked to a kernel ILLNESS case; KPI timestamps for tracing. */
@Entity(name = "E11CdCase")
@Table(name = "cd_case", schema = "travelhealth")
public class CdCase {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "cd_ref")
    private String cdRef;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "condition_code")
    private String conditionCode;
    @Column(name = "onset_date")
    private LocalDate onsetDate;
    @Column(name = "confirmed_on")
    private LocalDate confirmedOn;
    @Column(name = "confirmed_at")
    private Instant confirmedAt;
    @Column(name = "status")
    private String status;
    @Column(name = "notifiable")
    private boolean notifiable;
    @Column(name = "report_due_at")
    private Instant reportDueAt;
    @Column(name = "food_handler")
    private boolean foodHandler;
    @Column(name = "exclusion_decision")
    private String exclusionDecision;
    @Column(name = "exclusion_from")
    private LocalDate exclusionFrom;
    @Column(name = "exclusion_until")
    private LocalDate exclusionUntil;
    @Column(name = "return_criteria")
    private String returnCriteria;
    @Column(name = "trace_generated_at")
    private Instant traceGeneratedAt;
    @Column(name = "trace_hours")
    private BigDecimal traceHours;
    @Column(name = "last_traced_at")
    private Instant lastTracedAt;
    @Column(name = "cleared_at")
    private Instant clearedAt;
    @Column(name = "criteria_met")
    private String criteriaMet;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    /** New instance with a random id. */
    public static CdCase create() {
        CdCase e = new CdCase();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getCdRef() {
        return cdRef;
    }

    public void setCdRef(String v) {
        this.cdRef = v;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID v) {
        this.caseId = v;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public void setCaseRef(String v) {
        this.caseRef = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getConditionCode() {
        return conditionCode;
    }

    public void setConditionCode(String v) {
        this.conditionCode = v;
    }

    public LocalDate getOnsetDate() {
        return onsetDate;
    }

    public void setOnsetDate(LocalDate v) {
        this.onsetDate = v;
    }

    public LocalDate getConfirmedOn() {
        return confirmedOn;
    }

    public void setConfirmedOn(LocalDate v) {
        this.confirmedOn = v;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant v) {
        this.confirmedAt = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        this.status = v;
    }

    public boolean isNotifiable() {
        return notifiable;
    }

    public void setNotifiable(boolean v) {
        this.notifiable = v;
    }

    public Instant getReportDueAt() {
        return reportDueAt;
    }

    public void setReportDueAt(Instant v) {
        this.reportDueAt = v;
    }

    public boolean isFoodHandler() {
        return foodHandler;
    }

    public void setFoodHandler(boolean v) {
        this.foodHandler = v;
    }

    public String getExclusionDecision() {
        return exclusionDecision;
    }

    public void setExclusionDecision(String v) {
        this.exclusionDecision = v;
    }

    public LocalDate getExclusionFrom() {
        return exclusionFrom;
    }

    public void setExclusionFrom(LocalDate v) {
        this.exclusionFrom = v;
    }

    public LocalDate getExclusionUntil() {
        return exclusionUntil;
    }

    public void setExclusionUntil(LocalDate v) {
        this.exclusionUntil = v;
    }

    public String getReturnCriteria() {
        return returnCriteria;
    }

    public void setReturnCriteria(String v) {
        this.returnCriteria = v;
    }

    public Instant getTraceGeneratedAt() {
        return traceGeneratedAt;
    }

    public void setTraceGeneratedAt(Instant v) {
        this.traceGeneratedAt = v;
    }

    public BigDecimal getTraceHours() {
        return traceHours;
    }

    public void setTraceHours(BigDecimal v) {
        this.traceHours = v;
    }

    public Instant getLastTracedAt() {
        return lastTracedAt;
    }

    public void setLastTracedAt(Instant v) {
        this.lastTracedAt = v;
    }

    public Instant getClearedAt() {
        return clearedAt;
    }

    public void setClearedAt(Instant v) {
        this.clearedAt = v;
    }

    public String getCriteriaMet() {
        return criteriaMet;
    }

    public void setCriteriaMet(String v) {
        this.criteriaMet = v;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String v) {
        this.createdBy = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }

    public long getVersion() {
        return version;
    }
}
