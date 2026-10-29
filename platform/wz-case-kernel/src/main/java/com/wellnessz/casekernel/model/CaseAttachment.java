package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Photo / document attached to a case (E01-F02). Content is base64 + AES-256-GCM encrypted at field level. */
@Entity
@Table(name = "case_attachment", schema = "casemgmt")
public class CaseAttachment {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;
    @Column(name = "size_bytes", nullable = false)
    private long size;
    @Column(nullable = false, length = 64)
    private String sha256;
    @Basic(fetch = FetchType.LAZY)
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String contentBase64;
    @Column(name = "uploaded_by", nullable = false, length = 200)
    private String uploadedBy;
    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected CaseAttachment() {
    }

    public CaseAttachment(UUID caseId, String fileName, String contentType, long size, String sha256, String contentBase64,
                          String uploadedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.size = size;
        this.sha256 = sha256;
        this.contentBase64 = contentBase64;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSize() {
        return size;
    }

    public String getSha256() {
        return sha256;
    }

    public String getContentBase64() {
        return contentBase64;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void moveTo(UUID target) {
        this.caseId = target;
    }
}
