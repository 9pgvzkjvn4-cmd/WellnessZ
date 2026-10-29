package com.wellnessz.e08.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Task shared between the claims team, the nurse case manager and the adjuster (screened title). */
@Entity(name = "E08ClaimTask")
@Table(name = "claim_task", schema = "claims")
public class ClaimTask {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "title")
    private String title;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "assignee_role")
    private String assigneeRole;
    @Column(name = "status")
    private String status;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "completed_by")
    private String completedBy;

    public ClaimTask() {
        // JPA; populated through setters by the owning service
    }

    public static ClaimTask create() {
        ClaimTask x = new ClaimTask();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getAssigneeRole() { return assigneeRole; }
    public void setAssigneeRole(String v) { this.assigneeRole = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
}
