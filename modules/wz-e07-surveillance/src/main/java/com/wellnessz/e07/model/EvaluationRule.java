package com.wellnessz.e07.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Result evaluation rule (E07-F07). */
@Entity
@Table(name = "evaluation_rule", schema = "surveillance")
public class EvaluationRule {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "test")
    private String test;
    @Column(name = "rule_type")
    private String ruleType;
    @Column(name = "params", columnDefinition = "text")
    private String params;
    @Column(name = "follow_up_days")
    private int followUpDays;
    @Column(name = "regulatory_notification")
    private boolean regulatoryNotification;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public EvaluationRule() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getTest() { return test; }
    public void setTest(String v) { this.test = v; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String v) { this.ruleType = v; }
    public String getParams() { return params; }
    public void setParams(String v) { this.params = v; }
    public int getFollowUpDays() { return followUpDays; }
    public void setFollowUpDays(int v) { this.followUpDays = v; }
    public boolean isRegulatoryNotification() { return regulatoryNotification; }
    public void setRegulatoryNotification(boolean v) { this.regulatoryNotification = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
