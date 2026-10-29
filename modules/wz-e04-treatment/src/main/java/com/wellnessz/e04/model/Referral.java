package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** E-referral to a partner provider (E04-F02); reason, question, data set and report summary are encrypted. */
@Entity(name = "TxReferral")
@Table(name = "referral", schema = "treatment")
public class Referral {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "service_code")
    private String serviceCode;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "urgency")
    private String urgency;
    @Column(name = "status")
    private String status;
    @Column(name = "channel")
    private String channel;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason", columnDefinition = "text")
    private String reason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "question", columnDefinition = "text")
    private String question;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "data_set", columnDefinition = "text")
    private String dataSet;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "attachments")
    private String attachments;
    @Column(name = "letter_document_id")
    private UUID letterDocumentId;
    @Column(name = "report_document_id")
    private UUID reportDocumentId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "report_summary", columnDefinition = "text")
    private String reportSummary;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "fhir", columnDefinition = "text")
    private String fhir;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "declined_at")
    private Instant declinedAt;
    @Column(name = "decline_reason")
    private String declineReason;
    @Column(name = "appointment_at")
    private Instant appointmentAt;
    @Column(name = "appointment_location")
    private String appointmentLocation;
    @Column(name = "appointment_instructions")
    private String appointmentInstructions;
    @Column(name = "seen_at")
    private Instant seenAt;
    @Column(name = "report_due_at")
    private Instant reportDueAt;
    @Column(name = "report_received_at")
    private Instant reportReceivedAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;
    @Column(name = "chase_count")
    private int chaseCount;
    @Column(name = "last_chased_at")
    private Instant lastChasedAt;
    @Column(name = "escalated")
    private boolean escalated;
    @Column(name = "rating")
    private Integer rating;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "rating_comment", columnDefinition = "text")
    private String ratingComment;
    @Column(name = "rated_by")
    private String ratedBy;
    @Column(name = "source_type")
    private String sourceType;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "partner_read_at")
    private Instant partnerReadAt;

    public Referral() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String v) { this.serviceCode = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String v) { this.urgency = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getQuestion() { return question; }
    public void setQuestion(String v) { this.question = v; }
    public String getDataSet() { return dataSet; }
    public void setDataSet(String v) { this.dataSet = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String v) { this.attachments = v; }
    public UUID getLetterDocumentId() { return letterDocumentId; }
    public void setLetterDocumentId(UUID v) { this.letterDocumentId = v; }
    public UUID getReportDocumentId() { return reportDocumentId; }
    public void setReportDocumentId(UUID v) { this.reportDocumentId = v; }
    public String getReportSummary() { return reportSummary; }
    public void setReportSummary(String v) { this.reportSummary = v; }
    public String getFhir() { return fhir; }
    public void setFhir(String v) { this.fhir = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String v) { this.sentBy = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant v) { this.acceptedAt = v; }
    public Instant getDeclinedAt() { return declinedAt; }
    public void setDeclinedAt(Instant v) { this.declinedAt = v; }
    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String v) { this.declineReason = v; }
    public Instant getAppointmentAt() { return appointmentAt; }
    public void setAppointmentAt(Instant v) { this.appointmentAt = v; }
    public String getAppointmentLocation() { return appointmentLocation; }
    public void setAppointmentLocation(String v) { this.appointmentLocation = v; }
    public String getAppointmentInstructions() { return appointmentInstructions; }
    public void setAppointmentInstructions(String v) { this.appointmentInstructions = v; }
    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant v) { this.seenAt = v; }
    public Instant getReportDueAt() { return reportDueAt; }
    public void setReportDueAt(Instant v) { this.reportDueAt = v; }
    public Instant getReportReceivedAt() { return reportReceivedAt; }
    public void setReportReceivedAt(Instant v) { this.reportReceivedAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { this.cancelReason = v; }
    public int getChaseCount() { return chaseCount; }
    public void setChaseCount(int v) { this.chaseCount = v; }
    public Instant getLastChasedAt() { return lastChasedAt; }
    public void setLastChasedAt(Instant v) { this.lastChasedAt = v; }
    public boolean isEscalated() { return escalated; }
    public void setEscalated(boolean v) { this.escalated = v; }
    public Integer getRating() { return rating; }
    public void setRating(Integer v) { this.rating = v; }
    public String getRatingComment() { return ratingComment; }
    public void setRatingComment(String v) { this.ratingComment = v; }
    public String getRatedBy() { return ratedBy; }
    public void setRatedBy(String v) { this.ratedBy = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public Instant getPartnerReadAt() { return partnerReadAt; }
    public void setPartnerReadAt(Instant v) { this.partnerReadAt = v; }
}
