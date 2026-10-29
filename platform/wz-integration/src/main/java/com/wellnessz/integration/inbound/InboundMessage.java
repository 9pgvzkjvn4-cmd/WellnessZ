package com.wellnessz.integration.inbound;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Raw inbound message (FHIR, HL7 v2, partner JSON) kept for traceability and replay. Payload is encrypted at field level. */
@Entity
@Table(name = "inbound_message", schema = "platform")
public class InboundMessage {

    @Id
    private UUID id;
    @Column(nullable = false, length = 100)
    private String source;
    @Column(nullable = false, length = 40)
    private String format;
    @Column(name = "message_type", length = 100)
    private String messageType;
    @Column(name = "external_id", length = 200)
    private String externalId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String payload;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "received_by", nullable = false, length = 200)
    private String receivedBy;

    protected InboundMessage() {
    }

    public InboundMessage(String source, String format, String messageType, String externalId, String payload,
                          String receivedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.source = source;
        this.format = format;
        this.messageType = messageType;
        this.externalId = externalId;
        this.payload = payload;
        this.status = "RECEIVED";
        this.receivedBy = receivedBy;
        this.receivedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public String getFormat() {
        return format;
    }

    public String getMessageType() {
        return messageType;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getPayload() {
        return payload;
    }

    public String getStatus() {
        return status;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
