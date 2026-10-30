package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Clinical note of a support case (encrypted, insert-only). */
@Entity(name = "E09SupportNote")
@Table(name = "support_note", schema = "mentalhealth")
public class SupportNote {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text", columnDefinition = "text")
    private String text;
    @Column(name = "author")
    private String author;
    @Column(name = "created_at")
    private Instant createdAt;

    public SupportNote() {
        // JPA; populated through setters by the owning service
    }

    public static SupportNote create() {
        SupportNote x = new SupportNote();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
