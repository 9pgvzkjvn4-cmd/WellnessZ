package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Affected person of a CISM event: offer, response, session, 1- and 4-week follow-ups. */
@Entity(name = "E09CismAffected")
@Table(name = "cism_affected", schema = "mentalhealth")
public class CismAffected {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "duty_role")
    private String dutyRole;
    @Column(name = "offer_sent_at")
    private Instant offerSentAt;
    @Column(name = "response")
    private String response;
    @Column(name = "preferred_contact")
    private String preferredContact;
    @Column(name = "responded_at")
    private Instant respondedAt;
    @Column(name = "session_type")
    private String sessionType;
    @Column(name = "attended_at")
    private Instant attendedAt;
    @Column(name = "follow_up_1w_due")
    private LocalDate followUp1wDue;
    @Column(name = "follow_up_1w_done")
    private boolean followUp1wDone;
    @Column(name = "follow_up_1w_sent_at")
    private Instant followUp1wSentAt;
    @Column(name = "follow_up_4w_due")
    private LocalDate followUp4wDue;
    @Column(name = "follow_up_4w_done")
    private boolean followUp4wDone;
    @Column(name = "follow_up_4w_sent_at")
    private Instant followUp4wSentAt;
    @Column(name = "referred")
    private boolean referred;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public CismAffected() {
        // JPA; populated through setters by the owning service
    }

    public static CismAffected create() {
        CismAffected x = new CismAffected();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID v) { this.eventId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getDutyRole() { return dutyRole; }
    public void setDutyRole(String v) { this.dutyRole = v; }
    public Instant getOfferSentAt() { return offerSentAt; }
    public void setOfferSentAt(Instant v) { this.offerSentAt = v; }
    public String getResponse() { return response; }
    public void setResponse(String v) { this.response = v; }
    public String getPreferredContact() { return preferredContact; }
    public void setPreferredContact(String v) { this.preferredContact = v; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant v) { this.respondedAt = v; }
    public String getSessionType() { return sessionType; }
    public void setSessionType(String v) { this.sessionType = v; }
    public Instant getAttendedAt() { return attendedAt; }
    public void setAttendedAt(Instant v) { this.attendedAt = v; }
    public LocalDate getFollowUp1wDue() { return followUp1wDue; }
    public void setFollowUp1wDue(LocalDate v) { this.followUp1wDue = v; }
    public boolean isFollowUp1wDone() { return followUp1wDone; }
    public void setFollowUp1wDone(boolean v) { this.followUp1wDone = v; }
    public Instant getFollowUp1wSentAt() { return followUp1wSentAt; }
    public void setFollowUp1wSentAt(Instant v) { this.followUp1wSentAt = v; }
    public LocalDate getFollowUp4wDue() { return followUp4wDue; }
    public void setFollowUp4wDue(LocalDate v) { this.followUp4wDue = v; }
    public boolean isFollowUp4wDone() { return followUp4wDone; }
    public void setFollowUp4wDone(boolean v) { this.followUp4wDone = v; }
    public Instant getFollowUp4wSentAt() { return followUp4wSentAt; }
    public void setFollowUp4wSentAt(Instant v) { this.followUp4wSentAt = v; }
    public boolean isReferred() { return referred; }
    public void setReferred(boolean v) { this.referred = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
