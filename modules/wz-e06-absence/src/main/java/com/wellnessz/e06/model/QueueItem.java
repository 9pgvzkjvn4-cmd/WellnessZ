package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Early-intervention / long-term / AME review queue item raised by a trigger rule. */
@Entity(name = "AbsenceQueueItem")
@Table(name = "queue_item", schema = "absence")
public class QueueItem {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "rule_id")
    private UUID ruleId;
    @Column(name = "rule_code")
    private String ruleCode;
    @Column(name = "rule_name")
    private String ruleName;
    @Column(name = "action")
    private String action;
    @Column(name = "status")
    private String status;
    @Column(name = "raised_at")
    private Instant raisedAt;
    @Column(name = "sla_due_at")
    private Instant slaDueAt;
    @Column(name = "episode_id")
    private UUID episodeId;
    @Column(name = "detail")
    private String detail;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "assigned_to")
    private String assignedTo;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "first_contact_at")
    private Instant firstContactAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "closed_by")
    private String closedBy;
    @Column(name = "close_reason")
    private String closeReason;

    public QueueItem() {
        // JPA; populated through setters by the owning service
    }

    public static QueueItem create() {
        QueueItem x = new QueueItem();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID v) { this.ruleId = v; }
    public String getRuleCode() { return ruleCode; }
    public void setRuleCode(String v) { this.ruleCode = v; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String v) { this.ruleName = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getRaisedAt() { return raisedAt; }
    public void setRaisedAt(Instant v) { this.raisedAt = v; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant v) { this.slaDueAt = v; }
    public UUID getEpisodeId() { return episodeId; }
    public void setEpisodeId(UUID v) { this.episodeId = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String v) { this.assignedTo = v; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant v) { this.acceptedAt = v; }
    public Instant getFirstContactAt() { return firstContactAt; }
    public void setFirstContactAt(Instant v) { this.firstContactAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String v) { this.closedBy = v; }
    public String getCloseReason() { return closeReason; }
    public void setCloseReason(String v) { this.closeReason = v; }
}
