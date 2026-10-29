package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Secure chat message of a telehealth session (encrypted at rest). */
@Entity(name = "TxTelehealthMessage")
@Table(name = "telehealth_message", schema = "treatment")
public class TelehealthMessage {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "sender")
    private String sender;
    @Column(name = "sender_role")
    private String senderRole;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "text", columnDefinition = "text")
    private String text;
    @Column(name = "sent_at")
    private Instant sentAt;

    public TelehealthMessage() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID v) { this.sessionId = v; }
    public String getSender() { return sender; }
    public void setSender(String v) { this.sender = v; }
    public String getSenderRole() { return senderRole; }
    public void setSenderRole(String v) { this.senderRole = v; }
    public String getText() { return text; }
    public void setText(String v) { this.text = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
}
