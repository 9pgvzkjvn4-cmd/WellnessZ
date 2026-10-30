package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Action with owner and due date from a psychosocial risk assessment. */
@Entity(name = "E09PsychosocialAction")
@Table(name = "psychosocial_action", schema = "mentalhealth")
public class PsychosocialAction {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "assessment_id")
    private UUID assessmentId;
    @Column(name = "title")
    private String title;
    @Column(name = "owner")
    private String owner;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "status")
    private String status;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    public PsychosocialAction() {
        // JPA; populated through setters by the owning service
    }

    public static PsychosocialAction create() {
        PsychosocialAction x = new PsychosocialAction();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getAssessmentId() { return assessmentId; }
    public void setAssessmentId(UUID v) { this.assessmentId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getOwner() { return owner; }
    public void setOwner(String v) { this.owner = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
