package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Clinician annotation on a capture (E33-F11) – screened, encrypted, append-only. */
@Entity(name = "E33CaptureAnnotation")
@Table(name = "capture_annotation", schema = "devicecap")
public class CaptureAnnotation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "author")
    private String author;
    @Column(name = "created_at")
    private Instant createdAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text", columnDefinition = "text")
    private String text;

    protected CaptureAnnotation() {
        // JPA
    }

    public CaptureAnnotation(UUID sessionId, String author, Instant createdAt, String text) {
        this.id = UUID.randomUUID();
        this.sessionId = sessionId;
        this.author = author;
        this.createdAt = createdAt;
        this.text = text;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getAuthor() { return author; }
    public Instant getCreatedAt() { return createdAt; }
    public String getText() { return text; }
}
