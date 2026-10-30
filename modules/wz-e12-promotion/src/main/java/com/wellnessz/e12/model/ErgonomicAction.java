package com.wellnessz.e12.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Corrective action from an ergonomic assessment, tracked to closure. */
@Entity(name = "E12ErgonomicAction")
@Table(name = "ergonomic_action", schema = "promotion")
public class ErgonomicAction {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "assessment_id")
    private UUID assessmentId;
    @Column(name = "action_ref")
    private String actionRef;
    @Column(name = "title")
    private String title;
    @Column(name = "owner")
    private String owner;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "status")
    private String status;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "last_reminded_on")
    private LocalDate lastRemindedOn;
    @Version
    @Column(name = "version")
    private long version;

    public ErgonomicAction() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getAssessmentId() { return assessmentId; }
    public void setAssessmentId(UUID v) { this.assessmentId = v; }
    public String getActionRef() { return actionRef; }
    public void setActionRef(String v) { this.actionRef = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public LocalDate getLastRemindedOn() { return lastRemindedOn; }
    public void setLastRemindedOn(LocalDate v) { this.lastRemindedOn = v; }
    public long getVersion() { return version; }
}
