package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Referral to the EAP / counselling partner; feedback limited to status, attendance and closure reason. */
@Entity(name = "E09EapReferral")
@Table(name = "eap_referral", schema = "mentalhealth")
public class EapReferral {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "partner_code")
    private String partnerCode;
    @Column(name = "sessions_authorised")
    private int sessionsAuthorised;
    @Column(name = "urgency")
    private String urgency;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "status")
    private String status;
    @Column(name = "first_appointment_due_by")
    private LocalDate firstAppointmentDueBy;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "attendance")
    private String attendance;
    @Column(name = "closure_reason")
    private String closureReason;
    @Column(name = "feedback_at")
    private Instant feedbackAt;
    @Column(name = "feedback_by")
    private String feedbackBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public EapReferral() {
        // JPA; populated through setters by the owning service
    }

    public static EapReferral create() {
        EapReferral x = new EapReferral();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getPartnerCode() { return partnerCode; }
    public void setPartnerCode(String v) { this.partnerCode = v; }
    public int getSessionsAuthorised() { return sessionsAuthorised; }
    public void setSessionsAuthorised(int v) { this.sessionsAuthorised = v; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String v) { this.urgency = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDate getFirstAppointmentDueBy() { return firstAppointmentDueBy; }
    public void setFirstAppointmentDueBy(LocalDate v) { this.firstAppointmentDueBy = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String v) { this.sentBy = v; }
    public String getAttendance() { return attendance; }
    public void setAttendance(String v) { this.attendance = v; }
    public String getClosureReason() { return closureReason; }
    public void setClosureReason(String v) { this.closureReason = v; }
    public Instant getFeedbackAt() { return feedbackAt; }
    public void setFeedbackAt(Instant v) { this.feedbackAt = v; }
    public String getFeedbackBy() { return feedbackBy; }
    public void setFeedbackBy(String v) { this.feedbackBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
