package com.wellnessz.e08.model;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Statutory / insurer report form definition (seeded, pending Legal / HSE sign-off). */
@Entity(name = "E08ReportForm")
@Table(name = "report_form", schema = "claims")
public class ReportForm {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "jurisdiction")
    private String jurisdiction;
    @Column(name = "recipient_type")
    private String recipientType;
    @Column(name = "recipient_code")
    private String recipientCode;
    @Column(name = "title")
    private String title;
    @Column(name = "deadline_hours")
    private int deadlineHours;
    @Column(name = "specified_deadline_hours")
    private Integer specifiedDeadlineHours;
    @Column(name = "deadline_anchor")
    private String deadlineAnchor;
    @Column(name = "trigger_rule")
    private String triggerRule;
    @Column(name = "fields", columnDefinition = "text")
    private String fields;
    @Column(name = "sign_off_status")
    private String signOffStatus;
    @Column(name = "active")
    private boolean active;

    public ReportForm() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getJurisdiction() { return jurisdiction; }
    public void setJurisdiction(String v) { this.jurisdiction = v; }
    public String getRecipientType() { return recipientType; }
    public void setRecipientType(String v) { this.recipientType = v; }
    public String getRecipientCode() { return recipientCode; }
    public void setRecipientCode(String v) { this.recipientCode = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public int getDeadlineHours() { return deadlineHours; }
    public void setDeadlineHours(int v) { this.deadlineHours = v; }
    public Integer getSpecifiedDeadlineHours() { return specifiedDeadlineHours; }
    public void setSpecifiedDeadlineHours(Integer v) { this.specifiedDeadlineHours = v; }
    public String getDeadlineAnchor() { return deadlineAnchor; }
    public void setDeadlineAnchor(String v) { this.deadlineAnchor = v; }
    public String getTriggerRule() { return triggerRule; }
    public void setTriggerRule(String v) { this.triggerRule = v; }
    public String getFields() { return fields; }
    public void setFields(String v) { this.fields = v; }
    public String getSignOffStatus() { return signOffStatus; }
    public void setSignOffStatus(String v) { this.signOffStatus = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
}
