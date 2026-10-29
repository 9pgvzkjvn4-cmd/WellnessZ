package com.wellnessz.e08.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Version of the report answering a legal request (signed medico-legal report or provider IME report). */
@Entity(name = "E08LegalReport")
@Table(name = "legal_report", schema = "claims")
public class LegalReport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id")
    private UUID requestId;
    @Column(name = "version")
    private int version;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "author")
    private String author;
    @Column(name = "created_at")
    private Instant createdAt;

    public LegalReport() {
        // JPA; populated through setters by the owning service
    }

    public static LegalReport create() {
        LegalReport x = new LegalReport();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID v) { this.requestId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getAuthor() { return author; }
    public void setAuthor(String v) { this.author = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
