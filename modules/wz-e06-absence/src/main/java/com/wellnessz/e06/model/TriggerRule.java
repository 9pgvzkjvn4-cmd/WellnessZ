package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Configurable absence trigger rule (E06-F02); versioned on every change. */
@Entity(name = "AbsenceTriggerRule")
@Table(name = "trigger_rule", schema = "absence")
public class TriggerRule {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "subsidiary_code")
    private String subsidiaryCode;
    @Column(name = "rule_type")
    private String ruleType;
    @Column(name = "params")
    private String params;
    @Column(name = "action")
    private String action;
    @Column(name = "active")
    private boolean active;
    @Column(name = "version")
    private int version;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public TriggerRule() {
        // JPA; populated through setters by the owning service
    }

    public static TriggerRule create() {
        TriggerRule x = new TriggerRule();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getSubsidiaryCode() { return subsidiaryCode; }
    public void setSubsidiaryCode(String v) { this.subsidiaryCode = v; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String v) { this.ruleType = v; }
    public String getParams() { return params; }
    public void setParams(String v) { this.params = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
