package com.wellnessz.e05.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Log of every crew roster-status message for acknowledgement and reconciliation (E05-BL-012). */
@Entity
@Table(name = "roster_feed_message", schema = "fitness")
public class RosterFeedMessage {

    @Id
    @Column(name = "message_id")
    private String messageId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "fitness_status")
    private String fitnessStatus;
    @Column(name = "roster_status")
    private String rosterStatus;
    @Column(name = "valid_from")
    private LocalDate validFrom;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "operational_limitations")
    private String operationalLimitations;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "ack_at")
    private Instant ackAt;
    @Column(name = "ack_by")
    private String ackBy;
    @Column(name = "alerted_at")
    private Instant alertedAt;

    public RosterFeedMessage() {
        // JPA; populated through setters by the owning service
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String v) { this.messageId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getFitnessStatus() { return fitnessStatus; }
    public void setFitnessStatus(String v) { this.fitnessStatus = v; }
    public String getRosterStatus() { return rosterStatus; }
    public void setRosterStatus(String v) { this.rosterStatus = v; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate v) { this.validFrom = v; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate v) { this.validUntil = v; }
    public String getOperationalLimitations() { return operationalLimitations; }
    public void setOperationalLimitations(String v) { this.operationalLimitations = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public Instant getAckAt() { return ackAt; }
    public void setAckAt(Instant v) { this.ackAt = v; }
    public String getAckBy() { return ackBy; }
    public void setAckBy(String v) { this.ackBy = v; }
    public Instant getAlertedAt() { return alertedAt; }
    public void setAlertedAt(Instant v) { this.alertedAt = v; }
}
