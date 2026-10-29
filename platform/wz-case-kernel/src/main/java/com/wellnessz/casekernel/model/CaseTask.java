package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Actionable item on a case: call-backs (telephone triage), follow-ups, handovers, reminders, checklists. */
@Entity
@Table(name = "case_task", schema = "casemgmt")
public class CaseTask {

    public enum Type { CALL_BACK, FOLLOW_UP, HANDOVER, REMINDER, CHECKLIST, ENRICHMENT }

    public enum Status { OPEN, DONE, CANCELLED }

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private Type type;
    @Column(nullable = false, length = 300)
    private String title;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(length = 200)
    private String assignee;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    protected CaseTask() {
    }

    public CaseTask(UUID caseId, Type type, String title, Instant dueAt, String assignee, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.type = type;
        this.title = title.length() > 300 ? title.substring(0, 300) : title;
        this.dueAt = dueAt;
        this.assignee = assignee;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void complete(Instant now) {
        this.status = Status.DONE;
        this.completedAt = now;
    }

    public void cancel(Instant now) {
        this.status = Status.CANCELLED;
        this.completedAt = now;
    }

    public void reassign(String assignee) {
        this.assignee = assignee;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Type getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public String getAssignee() {
        return assignee;
    }

    public Status getStatus() {
        return status;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
