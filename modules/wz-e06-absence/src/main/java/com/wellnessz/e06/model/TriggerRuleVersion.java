package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Audit snapshot of a trigger rule version. */
@Entity(name = "AbsenceTriggerRuleVersion")
@Table(name = "trigger_rule_version", schema = "absence")
public class TriggerRuleVersion {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "rule_id")
    private UUID ruleId;
    @Column(name = "version")
    private int version;
    @Column(name = "snapshot")
    private String snapshot;
    @Column(name = "change_type")
    private String changeType;
    @Column(name = "changed_by")
    private String changedBy;
    @Column(name = "changed_at")
    private Instant changedAt;

    public TriggerRuleVersion() {
        // JPA; populated through setters by the owning service
    }

    public static TriggerRuleVersion create() {
        TriggerRuleVersion x = new TriggerRuleVersion();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID v) { this.ruleId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getSnapshot() { return snapshot; }
    public void setSnapshot(String v) { this.snapshot = v; }
    public String getChangeType() { return changeType; }
    public void setChangeType(String v) { this.changeType = v; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String v) { this.changedBy = v; }
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant v) { this.changedAt = v; }
}
