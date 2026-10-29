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

/** Outcome measure at a milestone (NPRS pain, functional scale, RTW, residual impairment). */
@Entity(name = "TxTreatmentOutcome")
@Table(name = "outcome", schema = "treatment")
public class TreatmentOutcome {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "milestone")
    private String milestone;
    @Column(name = "pain_score")
    private int painScore;
    @Column(name = "function_scale")
    private String functionScale;
    @Column(name = "function_score")
    private int functionScore;
    @Column(name = "rtw_date")
    private LocalDate rtwDate;
    @Column(name = "residual_impairment_pct")
    private Integer residualImpairmentPct;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public TreatmentOutcome() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getMilestone() { return milestone; }
    public void setMilestone(String v) { this.milestone = v; }
    public int getPainScore() { return painScore; }
    public void setPainScore(int v) { this.painScore = v; }
    public String getFunctionScale() { return functionScale; }
    public void setFunctionScale(String v) { this.functionScale = v; }
    public int getFunctionScore() { return functionScore; }
    public void setFunctionScore(int v) { this.functionScore = v; }
    public LocalDate getRtwDate() { return rtwDate; }
    public void setRtwDate(LocalDate v) { this.rtwDate = v; }
    public Integer getResidualImpairmentPct() { return residualImpairmentPct; }
    public void setResidualImpairmentPct(Integer v) { this.residualImpairmentPct = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
