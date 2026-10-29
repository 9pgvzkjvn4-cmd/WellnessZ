package com.wellnessz.documents.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.common.model.DataClassification;
import com.wellnessz.secrets.EncryptedStringConverter;

/**
 * A generated or ingested document (E03-F06). Content, merge fields and extracted text are encrypted at field level;
 * the search vector only holds keyed blind-index tokens, never plaintext words.
 */
@Entity
@Table(name = "document", schema = "docs")
public class Document {

    public enum Status { DRAFT, SIGNED, SUPERSEDED, REVOKED }

    @Id
    private UUID id;
    @Column(name = "document_number", nullable = false, unique = true, length = 30)
    private String documentNumber;
    @Column(name = "series_id", nullable = false)
    private UUID seriesId;
    @Column(nullable = false)
    private int version;
    @Column(nullable = false, length = 40)
    private String type;
    @Column(name = "template_code", length = 60)
    private String templateCode;
    @Column(nullable = false, length = 300)
    private String title;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DataClassification classification;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String content;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String fields;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;
    @Column(name = "signed_by", length = 200)
    private String signedBy;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "signature_sha256", length = 64)
    private String signatureSha256;
    @Column(name = "legal_hold", nullable = false)
    private boolean legalHold;
    @Column(name = "legal_hold_reason", length = 500)
    private String legalHoldReason;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Document() {
    }

    public Document(UUID id, String documentNumber, UUID seriesId, int version, String type, String templateCode, String title,
                    String employeeId, UUID caseId, DataClassification classification, String createdBy, Instant now) {
        this.id = id;
        this.documentNumber = documentNumber;
        this.seriesId = seriesId;
        this.version = version;
        this.type = type;
        this.templateCode = templateCode;
        this.title = title;
        this.employeeId = employeeId;
        this.caseId = caseId;
        this.classification = classification;
        this.status = Status.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void setContent(String contentType, byte[] bytes, String sha256, String base64) {
        this.contentType = contentType;
        this.sizeBytes = bytes.length;
        this.sha256 = sha256;
        this.content = base64;
    }

    public void sign(String by, Instant at, String signatureSha256) {
        this.status = Status.SIGNED;
        this.signedBy = by;
        this.signedAt = at;
        this.signatureSha256 = signatureSha256;
    }

    public UUID getId() { return id; }
    public String getDocumentNumber() { return documentNumber; }
    public UUID getSeriesId() { return seriesId; }
    public int getVersion() { return version; }
    public String getType() { return type; }
    public String getTemplateCode() { return templateCode; }
    public String getTitle() { return title; }
    public String getEmployeeId() { return employeeId; }
    public UUID getCaseId() { return caseId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public DataClassification getClassification() { return classification; }
    public String getContentType() { return contentType; }
    public int getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getContent() { return content; }
    public String getFields() { return fields; }
    public void setFields(String fields) { this.fields = fields; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public String getSignedBy() { return signedBy; }
    public Instant getSignedAt() { return signedAt; }
    public String getSignatureSha256() { return signatureSha256; }
    public boolean isLegalHold() { return legalHold; }
    public String getLegalHoldReason() { return legalHoldReason; }
    public void setLegalHold(boolean hold, String reason) { this.legalHold = hold; this.legalHoldReason = reason; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void acknowledge(Instant at) { if (acknowledgedAt == null) { this.acknowledgedAt = at; } }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
