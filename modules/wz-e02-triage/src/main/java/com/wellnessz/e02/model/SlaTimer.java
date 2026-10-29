package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Running SLA timer of a case milestone. Status: RUNNING, PAUSED, MET, BREACHED, CANCELLED. */
@Entity
@Table(name = "sla_timer", schema = "triage")
public class SlaTimer {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(nullable = false, length = 30)
    private String milestone;
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;
    @Column(name = "due_at", nullable = false)
    private Instant dueAt;
    @Column(name = "amber_at", nullable = false)
    private Instant amberAt;
    @Column(name = "stopped_at")
    private Instant stoppedAt;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "paused_remaining_seconds")
    private Long pausedRemainingSeconds;
    @Column(name = "amber_notified", nullable = false)
    private boolean amberNotified;
    @Column(name = "breached_at")
    private Instant breachedAt;

    public SlaTimer() {
        // JPA; fields are populated through setters by the owning service
    }


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public String getMilestone() {
        return milestone;
    }

    public void setMilestone(String milestone) {
        this.milestone = milestone;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant dueAt) {
        this.dueAt = dueAt;
    }

    public Instant getAmberAt() {
        return amberAt;
    }

    public void setAmberAt(Instant amberAt) {
        this.amberAt = amberAt;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public void setStoppedAt(Instant stoppedAt) {
        this.stoppedAt = stoppedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getPausedRemainingSeconds() {
        return pausedRemainingSeconds;
    }

    public void setPausedRemainingSeconds(Long pausedRemainingSeconds) {
        this.pausedRemainingSeconds = pausedRemainingSeconds;
    }

    public boolean isAmberNotified() {
        return amberNotified;
    }

    public void setAmberNotified(boolean amberNotified) {
        this.amberNotified = amberNotified;
    }

    public Instant getBreachedAt() {
        return breachedAt;
    }

    public void setBreachedAt(Instant breachedAt) {
        this.breachedAt = breachedAt;
    }
}
