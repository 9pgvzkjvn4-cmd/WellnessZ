package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An absence episode reconciled to the HRIS employee master. The reason category is OH-only. */
@Entity(name = "E06Episode")
@Table(name = "episode", schema = "absence")
public class Episode {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "source_system")
    private String sourceSystem;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "employee_id")
    private String employeeId;
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
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "feed_run_id")
    private UUID feedRunId;
    @Column(name = "lta_signalled")
    private boolean ltaSignalled;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Episode() {
        // JPA; populated through setters by the owning service
    }

    public static Episode create() {
        Episode x = new Episode();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String v) { this.sourceSystem = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
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
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public UUID getFeedRunId() { return feedRunId; }
    public void setFeedRunId(UUID v) { this.feedRunId = v; }
    public boolean isLtaSignalled() { return ltaSignalled; }
    public void setLtaSignalled(boolean v) { this.ltaSignalled = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
