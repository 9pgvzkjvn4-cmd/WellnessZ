package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A run of the absence feed (push from HRIS / T&amp;A / crew systems or a scheduled pull) with data-quality counters. */
@Entity(name = "AbsenceFeedRun")
@Table(name = "feed_run", schema = "absence")
public class FeedRun {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "source_system")
    private String sourceSystem;
    @Column(name = "run_type")
    private String runType;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "finished_at")
    private Instant finishedAt;
    @Column(name = "received")
    private int received;
    @Column(name = "matched")
    private int matched;
    @Column(name = "unmatched")
    private int unmatched;
    @Column(name = "updated")
    private int updated;
    @Column(name = "triggered")
    private int triggered;
    @Column(name = "errors")
    private int errors;
    @Column(name = "error_detail")
    private String errorDetail;
    @Column(name = "started_by")
    private String startedBy;

    public FeedRun() {
        // JPA; populated through setters by the owning service
    }

    public static FeedRun create() {
        FeedRun x = new FeedRun();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String v) { this.sourceSystem = v; }
    public String getRunType() { return runType; }
    public void setRunType(String v) { this.runType = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { this.finishedAt = v; }
    public int getReceived() { return received; }
    public void setReceived(int v) { this.received = v; }
    public int getMatched() { return matched; }
    public void setMatched(int v) { this.matched = v; }
    public int getUnmatched() { return unmatched; }
    public void setUnmatched(int v) { this.unmatched = v; }
    public int getUpdated() { return updated; }
    public void setUpdated(int v) { this.updated = v; }
    public int getTriggered() { return triggered; }
    public void setTriggered(int v) { this.triggered = v; }
    public int getErrors() { return errors; }
    public void setErrors(int v) { this.errors = v; }
    public String getErrorDetail() { return errorDetail; }
    public void setErrorDetail(String v) { this.errorDetail = v; }
    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String v) { this.startedBy = v; }
}
