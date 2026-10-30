package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Open urgent-risk item: same-day call back by a named clinician (kernel CALL_BACK task attached). */
@Entity(name = "E09UrgentItem")
@Table(name = "urgent_item", schema = "mentalhealth")
public class UrgentItem {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "screen_id")
    private UUID screenId;
    @Column(name = "kernel_task_id")
    private UUID kernelTaskId;
    @Column(name = "trigger_code")
    private String triggerCode;
    @Column(name = "call_back_by")
    private Instant callBackBy;
    @Column(name = "status")
    private String status;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "resolved_by")
    private String resolvedBy;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public UrgentItem() {
        // JPA; populated through setters by the owning service
    }

    public static UrgentItem create() {
        UrgentItem x = new UrgentItem();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public UUID getScreenId() { return screenId; }
    public void setScreenId(UUID v) { this.screenId = v; }
    public UUID getKernelTaskId() { return kernelTaskId; }
    public void setKernelTaskId(UUID v) { this.kernelTaskId = v; }
    public String getTriggerCode() { return triggerCode; }
    public void setTriggerCode(String v) { this.triggerCode = v; }
    public Instant getCallBackBy() { return callBackBy; }
    public void setCallBackBy(Instant v) { this.callBackBy = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(String v) { this.resolvedBy = v; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { this.resolvedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
