package com.wellnessz.e01.trigger;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Log of system triggers and their outcome (E01-F04). Holds no clinical values – only codes and outcome. */
@Entity
@Table(name = "trigger_event", schema = "intake")
public class TriggerEvent {

    public enum Type { ABSENCE, SURVEILLANCE_RESULT, FITNESS_DECLARATION, DA_TEST }

    public enum Outcome { CASE_CREATED, CASE_EXISTS, BELOW_THRESHOLD, NOT_ABNORMAL }

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 30)
    private Type type;
    @Column(name = "source_ref", nullable = false, length = 100)
    private String sourceRef;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "detail_code", length = 60)
    private String detailCode;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected TriggerEvent() {
    }

    public TriggerEvent(Type type, String sourceRef, String employeeId, String detailCode, Outcome outcome, UUID caseId, Instant now) {
        this.id = UUID.randomUUID();
        this.type = type;
        this.sourceRef = sourceRef;
        this.employeeId = employeeId;
        this.detailCode = detailCode;
        this.outcome = outcome;
        this.caseId = caseId;
        this.receivedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getDetailCode() {
        return detailCode;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
