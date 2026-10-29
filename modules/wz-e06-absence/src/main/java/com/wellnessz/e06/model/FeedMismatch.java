package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A feed row whose employee reference could not be reconciled against the employee master. */
@Entity(name = "AbsenceFeedMismatch")
@Table(name = "feed_mismatch", schema = "absence")
public class FeedMismatch {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "run_id")
    private UUID runId;
    @Column(name = "source_system")
    private String sourceSystem;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "employee_ref")
    private String employeeRef;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "absence_type")
    private String absenceType;
    @Column(name = "reason_category")
    private String reasonCategory;
    @Column(name = "certified")
    private boolean certified;
    @Column(name = "reason")
    private String reason;
    @Column(name = "status")
    private String status;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "resolved_by")
    private String resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "resolved_employee_id")
    private String resolvedEmployeeId;
    @Column(name = "resolution_note")
    private String resolutionNote;
    @Column(name = "episode_id")
    private UUID episodeId;

    public FeedMismatch() {
        // JPA; populated through setters by the owning service
    }

    public static FeedMismatch create() {
        FeedMismatch x = new FeedMismatch();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getRunId() { return runId; }
    public void setRunId(UUID v) { this.runId = v; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String v) { this.sourceSystem = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getEmployeeRef() { return employeeRef; }
    public void setEmployeeRef(String v) { this.employeeRef = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getAbsenceType() { return absenceType; }
    public void setAbsenceType(String v) { this.absenceType = v; }
    public String getReasonCategory() { return reasonCategory; }
    public void setReasonCategory(String v) { this.reasonCategory = v; }
    public boolean isCertified() { return certified; }
    public void setCertified(boolean v) { this.certified = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String v) { this.resolvedBy = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public String getResolvedEmployeeId() { return resolvedEmployeeId; }
    public void setResolvedEmployeeId(String v) { this.resolvedEmployeeId = v; }
    public String getResolutionNote() { return resolutionNote; }
    public void setResolutionNote(String v) { this.resolutionNote = v; }
    public UUID getEpisodeId() { return episodeId; }
    public void setEpisodeId(UUID v) { this.episodeId = v; }
}
