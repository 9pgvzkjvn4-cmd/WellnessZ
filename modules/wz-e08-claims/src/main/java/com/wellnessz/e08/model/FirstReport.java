package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** First report of injury to an authority or insurer (E08-F02). Field values are encrypted. */
@Entity(name = "E08FirstReport")
@Table(name = "first_report", schema = "claims")
public class FirstReport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "report_ref")
    private String reportRef;
    @Column(name = "claim_id")
    private UUID claimId;
    @Column(name = "form_code")
    private String formCode;
    @Column(name = "recipient_type")
    private String recipientType;
    @Column(name = "recipient_code")
    private String recipientCode;
    @Column(name = "status")
    private String status;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "specified_injury")
    private boolean specifiedInjury;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "fields", columnDefinition = "text")
    private String fields;
    @Column(name = "channel")
    private String channel;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "message_id")
    private String messageId;
    @Column(name = "submitted_at")
    private Instant submittedAt;
    @Column(name = "submitted_by")
    private String submittedBy;
    @Column(name = "ack_ref")
    private String ackRef;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "ack_document_id")
    private UUID ackDocumentId;
    @Column(name = "rejection_reason")
    private String rejectionReason;
    @Column(name = "reminded_at")
    private Instant remindedAt;
    @Column(name = "escalated_at")
    private Instant escalatedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public FirstReport() {
        // JPA; populated through setters by the owning service
    }

    public static FirstReport create() {
        FirstReport x = new FirstReport();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getReportRef() { return reportRef; }
    public void setReportRef(String v) { this.reportRef = v; }
    public UUID getClaimId() { return claimId; }
    public void setClaimId(UUID v) { this.claimId = v; }
    public String getFormCode() { return formCode; }
    public void setFormCode(String v) { this.formCode = v; }
    public String getRecipientType() { return recipientType; }
    public void setRecipientType(String v) { this.recipientType = v; }
    public String getRecipientCode() { return recipientCode; }
    public void setRecipientCode(String v) { this.recipientCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant v) { this.dueAt = v; }
    public boolean isSpecifiedInjury() { return specifiedInjury; }
    public void setSpecifiedInjury(boolean v) { this.specifiedInjury = v; }
    public String getFields() { return fields; }
    public void setFields(String v) { this.fields = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String v) { this.messageId = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String v) { this.submittedBy = v; }
    public String getAckRef() { return ackRef; }
    public void setAckRef(String v) { this.ackRef = v; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant v) { this.acknowledgedAt = v; }
    public UUID getAckDocumentId() { return ackDocumentId; }
    public void setAckDocumentId(UUID v) { this.ackDocumentId = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public Instant getRemindedAt() { return remindedAt; }
    public void setRemindedAt(Instant v) { this.remindedAt = v; }
    public Instant getEscalatedAt() { return escalatedAt; }
    public void setEscalatedAt(Instant v) { this.escalatedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
