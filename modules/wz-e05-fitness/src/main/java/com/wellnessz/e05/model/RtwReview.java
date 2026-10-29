package com.wellnessz.e05.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Scheduled review of an RTW plan (E05-BL-009). */
@Entity
@Table(name = "rtw_review", schema = "fitness")
public class RtwReview {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "plan_id")
    private UUID planId;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "completed_by")
    private String completedBy;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "note")
    private String note;
    @Column(name = "reminded_at")
    private Instant remindedAt;
    @Column(name = "escalated_at")
    private Instant escalatedAt;

    public RtwReview() {
        // JPA; populated through setters by the owning service
    }

    public static RtwReview create() {
        RtwReview x = new RtwReview();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID v) { this.planId = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getRemindedAt() { return remindedAt; }
    public void setRemindedAt(Instant v) { this.remindedAt = v; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant v) { this.escalatedAt = v; }
}
