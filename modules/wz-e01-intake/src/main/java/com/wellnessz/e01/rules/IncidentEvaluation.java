package com.wellnessz.e01.rules;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Outcome of rule evaluation per incident – the basis of incident-to-case reconciliation (E01-BL-004/005). */
@Entity
@Table(name = "incident_evaluation", schema = "intake")
public class IncidentEvaluation {

    public enum Outcome { AUTO_CREATED, SUGGESTED, ACCEPTED, DISMISSED, IGNORED, NO_MATCH }

    @Id
    @Column(name = "incident_id")
    private UUID incidentId;
    @Column(name = "rule_id")
    private Long ruleId;
    @Column(name = "rule_name", length = 200)
    private String ruleName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "dismiss_reason", length = 40)
    private String dismissReason;
    @Column(name = "dismiss_note", length = 500)
    private String dismissNote;
    @Column(name = "decided_by", length = 200)
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "reporter_email", length = 320)
    private String reporterEmail;
    @Column(name = "evaluated_at", nullable = false)
    private Instant evaluatedAt;

    protected IncidentEvaluation() {
    }

    public IncidentEvaluation(UUID incidentId, IntakeRule rule, Outcome outcome, String reporterEmail, Instant now) {
        this.incidentId = incidentId;
        this.ruleId = rule == null ? null : rule.getId();
        this.ruleName = rule == null ? null : rule.getName();
        this.outcome = outcome;
        this.reporterEmail = reporterEmail;
        this.evaluatedAt = now;
    }

    public void caseCreated(UUID caseId, Outcome outcome, String actor, Instant now) {
        this.caseId = caseId;
        this.outcome = outcome;
        this.decidedBy = actor;
        this.decidedAt = now;
    }

    public void dismiss(String reason, String note, String actor, Instant now) {
        this.outcome = Outcome.DISMISSED;
        this.dismissReason = reason;
        this.dismissNote = note;
        this.decidedBy = actor;
        this.decidedAt = now;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public Long getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getDismissReason() {
        return dismissReason;
    }

    public String getDismissNote() {
        return dismissNote;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public Instant getEvaluatedAt() {
        return evaluatedAt;
    }
}
