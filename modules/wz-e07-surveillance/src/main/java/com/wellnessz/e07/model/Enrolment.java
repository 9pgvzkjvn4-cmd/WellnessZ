package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/** An employee enrolled in a programme with the computed next due date (E07-F02). */
@Entity
@Table(name = "enrolment", schema = "surveillance")
public class Enrolment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "programme_code")
    private String programmeCode;
    @Column(name = "reason")
    private String reason;
    @Column(name = "active")
    private boolean active;
    @Column(name = "enrolled_on")
    private LocalDate enrolledOn;
    @Column(name = "last_completed_on")
    private LocalDate lastCompletedOn;
    @Column(name = "last_outcome")
    private String lastOutcome;
    @Column(name = "next_due_on")
    private LocalDate nextDueOn;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "exempt_reason", columnDefinition = "text")
    private String exemptReason;
    @Column(name = "exempt_until")
    private LocalDate exemptUntil;
    @Column(name = "exempt")
    private boolean exempt;
    @Column(name = "invitations")
    private int invitations;
    @Column(name = "last_invited_at")
    private Instant lastInvitedAt;
    @Column(name = "escalated_at")
    private Instant escalatedAt;
    @Column(name = "due_event_for")
    private LocalDate dueEventFor;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    public Enrolment() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getProgrammeCode() { return programmeCode; }
    public void setProgrammeCode(String v) { this.programmeCode = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public LocalDate getEnrolledOn() { return enrolledOn; }
    public void setEnrolledOn(LocalDate v) { this.enrolledOn = v; }
    public LocalDate getLastCompletedOn() { return lastCompletedOn; }
    public void setLastCompletedOn(LocalDate v) { this.lastCompletedOn = v; }
    public String getLastOutcome() { return lastOutcome; }
    public void setLastOutcome(String v) { this.lastOutcome = v; }
    public LocalDate getNextDueOn() { return nextDueOn; }
    public void setNextDueOn(LocalDate v) { this.nextDueOn = v; }
    public String getExemptReason() { return exemptReason; }
    public void setExemptReason(String v) { this.exemptReason = v; }
    public LocalDate getExemptUntil() { return exemptUntil; }
    public void setExemptUntil(LocalDate v) { this.exemptUntil = v; }
    public boolean isExempt() { return exempt; }
    public void setExempt(boolean v) { this.exempt = v; }
    public int getInvitations() { return invitations; }
    public void setInvitations(int v) { this.invitations = v; }
    public Instant getLastInvitedAt() { return lastInvitedAt; }
    public void setLastInvitedAt(Instant v) { this.lastInvitedAt = v; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant v) { this.escalatedAt = v; }
    public LocalDate getDueEventFor() { return dueEventFor; }
    public void setDueEventFor(LocalDate v) { this.dueEventFor = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
}
