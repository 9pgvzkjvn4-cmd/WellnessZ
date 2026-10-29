package com.wellnessz.documents.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Retention schedule entry per record class (E03-BL-017), approved by the DPO. */
@Entity
@Table(name = "retention_schedule", schema = "docs")
public class RetentionRule {

    @Id
    @Column(name = "record_class", length = 40)
    private String recordClass;
    @Column(name = "retention_years", nullable = false)
    private int retentionYears;
    @Column(name = "trigger_event", nullable = false, length = 30)
    private String triggerEvent;
    @Column(name = "legal_basis", nullable = false, length = 500)
    private String legalBasis;
    @Column(name = "approved_by", length = 200)
    private String approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RetentionRule() {
    }

    public RetentionRule(String recordClass) {
        this.recordClass = recordClass;
    }

    public void update(int years, String trigger, String basis, String by, Instant at) {
        this.retentionYears = years;
        this.triggerEvent = trigger;
        this.legalBasis = basis;
        this.approvedBy = by;
        this.approvedAt = at;
        this.updatedBy = by;
        this.updatedAt = at;
    }

    public String getRecordClass() { return recordClass; }
    public int getRetentionYears() { return retentionYears; }
    public String getTriggerEvent() { return triggerEvent; }
    public String getLegalBasis() { return legalBasis; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
}
