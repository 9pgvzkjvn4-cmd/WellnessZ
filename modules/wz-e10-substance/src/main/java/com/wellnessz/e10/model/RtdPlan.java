package com.wellnessz.e10.model;

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

/** Return-to-duty and follow-up testing plan after a verified positive or refusal (E10-F04). */
@Entity(name = "E10RtdPlan")
@Table(name = "rtd_plan", schema = "substance")
public class RtdPlan {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "test_id")
    private UUID testId;
    @Column(name = "status")
    private String status;
    @Column(name = "evaluator")
    private String evaluator;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "treatment_provider", columnDefinition = "text")
    private String treatmentProvider;
    @Column(name = "follow_up_tests")
    private int followUpTests;
    @Column(name = "follow_up_months")
    private int followUpMonths;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "evaluation_outcome")
    private String evaluationOutcome;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "evaluation_note", columnDefinition = "text")
    private String evaluationNote;
    @Column(name = "evaluated_by")
    private String evaluatedBy;
    @Column(name = "evaluated_at")
    private Instant evaluatedAt;
    @Column(name = "rtd_test_id")
    private UUID rtdTestId;
    @Column(name = "returned_at")
    private Instant returnedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public RtdPlan() {
        // JPA; populated through setters by the owning service
    }

    public static RtdPlan create() {
        RtdPlan x = new RtdPlan();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public UUID getTestId() { return testId; }
    public void setTestId(UUID v) { this.testId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getEvaluator() { return evaluator; }
    public void setEvaluator(String v) { this.evaluator = v; }
    public String getTreatmentProvider() { return treatmentProvider; }
    public void setTreatmentProvider(String v) { this.treatmentProvider = v; }
    public int getFollowUpTests() { return followUpTests; }
    public void setFollowUpTests(int v) { this.followUpTests = v; }
    public int getFollowUpMonths() { return followUpMonths; }
    public void setFollowUpMonths(int v) { this.followUpMonths = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getEvaluationOutcome() { return evaluationOutcome; }
    public void setEvaluationOutcome(String v) { this.evaluationOutcome = v; }
    public String getEvaluationNote() { return evaluationNote; }
    public void setEvaluationNote(String v) { this.evaluationNote = v; }
    public String getEvaluatedBy() { return evaluatedBy; }
    public void setEvaluatedBy(String v) { this.evaluatedBy = v; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant v) { this.evaluatedAt = v; }
    public UUID getRtdTestId() { return rtdTestId; }
    public void setRtdTestId(UUID v) { this.rtdTestId = v; }
    public Instant getReturnedAt() { return returnedAt; }
    public void setReturnedAt(Instant v) { this.returnedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
