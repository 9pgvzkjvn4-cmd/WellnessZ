package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Long-term absence pathway attached to a kernel ABSENCE case (E06-F04). */
@Entity
@Table(name = "lta_pathway", schema = "absence")
public class LtaPathway {

    @Id
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "episode_id")
    private UUID episodeId;
    @Column(name = "absence_start")
    private LocalDate absenceStart;
    @Column(name = "status")
    private String status;
    @Column(name = "hr_stage")
    private String hrStage;
    @Column(name = "hr_stage_note")
    private String hrStageNote;
    @Column(name = "hr_stage_by")
    private String hrStageBy;
    @Column(name = "hr_stage_at")
    private Instant hrStageAt;
    @Column(name = "rtw_date")
    private LocalDate rtwDate;
    @Column(name = "rtw_plan_id")
    private UUID rtwPlanId;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "closed_at")
    private Instant closedAt;

    public LtaPathway() {
        // JPA; populated through setters by the owning service
    }

    public static LtaPathway create() {
        LtaPathway x = new LtaPathway();
        x.caseId = UUID.randomUUID();
        return x;
    }

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getEpisodeId() { return episodeId; }
    public void setEpisodeId(UUID v) { this.episodeId = v; }
    public LocalDate getAbsenceStart() { return absenceStart; }
    public void setAbsenceStart(LocalDate v) { this.absenceStart = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getHrStage() { return hrStage; }
    public void setHrStage(String v) { this.hrStage = v; }
    public String getHrStageNote() { return hrStageNote; }
    public void setHrStageNote(String v) { this.hrStageNote = v; }
    public String getHrStageBy() { return hrStageBy; }
    public void setHrStageBy(String v) { this.hrStageBy = v; }
    public Instant getHrStageAt() { return hrStageAt; }
    public void setHrStageAt(Instant v) { this.hrStageAt = v; }
    public LocalDate getRtwDate() { return rtwDate; }
    public void setRtwDate(LocalDate v) { this.rtwDate = v; }
    public UUID getRtwPlanId() { return rtwPlanId; }
    public void setRtwPlanId(UUID v) { this.rtwPlanId = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
