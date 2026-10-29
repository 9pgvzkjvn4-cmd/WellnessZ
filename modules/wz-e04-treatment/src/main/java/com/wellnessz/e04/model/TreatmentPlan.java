package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Versioned treatment plan of a case (E04-F01); goals and interventions are clinical and encrypted. */
@Entity(name = "TxTreatmentPlan")
@Table(name = "treatment_plan", schema = "treatment")
public class TreatmentPlan {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "series_id")
    private UUID seriesId;
    @Column(name = "version")
    private int version;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "status")
    private String status;
    @Column(name = "template_code")
    private String templateCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "goals", columnDefinition = "text")
    private String goals;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "interventions", columnDefinition = "text")
    private String interventions;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "expected_recovery_days")
    private Integer expectedRecoveryDays;
    @Column(name = "guideline_code")
    private String guidelineCode;
    @Column(name = "variance_threshold_pct")
    private Integer varianceThresholdPct;
    @Column(name = "responsible_clinician")
    private String responsibleClinician;
    @Column(name = "document_id")
    private UUID documentId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "status_note", columnDefinition = "text")
    private String statusNote;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public TreatmentPlan() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSeriesId() { return seriesId; }
    public void setSeriesId(UUID v) { this.seriesId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String v) { this.templateCode = v; }
    public String getGoals() { return goals; }
    public void setGoals(String v) { this.goals = v; }
    public String getInterventions() { return interventions; }
    public void setInterventions(String v) { this.interventions = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public Integer getExpectedRecoveryDays() { return expectedRecoveryDays; }
    public void setExpectedRecoveryDays(Integer v) { this.expectedRecoveryDays = v; }
    public String getGuidelineCode() { return guidelineCode; }
    public void setGuidelineCode(String v) { this.guidelineCode = v; }
    public Integer getVarianceThresholdPct() { return varianceThresholdPct; }
    public void setVarianceThresholdPct(Integer v) { this.varianceThresholdPct = v; }
    public String getResponsibleClinician() { return responsibleClinician; }
    public void setResponsibleClinician(String v) { this.responsibleClinician = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getStatusNote() { return statusNote; }
    public void setStatusNote(String v) { this.statusNote = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
