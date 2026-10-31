package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Photo of a device display or printout, kept (encrypted, hash-recorded) as evidence of an OCR-verified value. */
@Entity(name = "E33OcrImage")
@Table(name = "ocr_image", schema = "devicecap")
public class OcrImage {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "device_instance_id")
    private String deviceInstanceId;
    @Column(name = "model_code")
    private String modelCode;
    @Column(name = "uploaded_by")
    private String uploadedBy;
    @Column(name = "uploaded_at")
    private Instant uploadedAt;
    @Column(name = "media_type")
    private String mediaType;
    @Column(name = "size_bytes")
    private long sizeBytes;
    @Column(name = "sha256")
    private String sha256;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "content", columnDefinition = "text")
    private String content;
    @Column(name = "retention_class")
    private String retentionClass = "DEVICE_RAW_UNFILED";

    protected OcrImage() {
        // JPA
    }

    public OcrImage(String deviceInstanceId, String modelCode, String uploadedBy, Instant uploadedAt, String mediaType, long sizeBytes,
                    String sha256, String base64Content) {
        this.id = UUID.randomUUID();
        this.deviceInstanceId = deviceInstanceId;
        this.modelCode = modelCode;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
        this.mediaType = mediaType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.content = base64Content;
    }

    public UUID getId() { return id; }
    public String getDeviceInstanceId() { return deviceInstanceId; }
    public String getModelCode() { return modelCode; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getMediaType() { return mediaType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getContent() { return content; }
    public String getRetentionClass() { return retentionClass; }
}
