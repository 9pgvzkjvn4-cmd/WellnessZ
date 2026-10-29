package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Signed, immutable addendum to a signed encounter. */
@Entity
@Table(name = "encounter_addendum", schema = "ohrecord")
public class EncounterAddendum {

    @Id
    private UUID id;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text", columnDefinition = "text")
    private String text;
    @Column(name = "author")
    private String author;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "sha256")
    private String sha256;

    public EncounterAddendum() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(UUID encounterId) {
        this.encounterId = encounterId;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Instant getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(Instant signedAt) {
        this.signedAt = signedAt;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }
}
