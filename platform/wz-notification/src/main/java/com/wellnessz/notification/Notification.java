package com.wellnessz.notification;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Queued notification. The rendered body is content-free by construction (see {@link NotificationTemplates}). */
@Entity
@Table(name = "notification", schema = "platform")
public class Notification {

    public enum Status { SCHEDULED, SENT, FAILED }

    @Id
    private UUID id;
    @Column(name = "recipient_id", nullable = false, length = 64)
    private String recipientId;
    @Column(nullable = false, length = 320)
    private String address;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Priority priority;
    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;
    @Column(nullable = false, length = 1000)
    private String body;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(nullable = false)
    private int attempts;

    protected Notification() {
    }

    public Notification(String recipientId, String address, Channel channel, Priority priority, String templateKey,
                        String body, Instant scheduledAt) {
        this.id = UUID.randomUUID();
        this.recipientId = recipientId;
        this.address = address;
        this.channel = channel;
        this.priority = priority;
        this.templateKey = templateKey;
        this.body = body;
        this.status = Status.SCHEDULED;
        this.scheduledAt = scheduledAt;
    }

    void sent(Instant now) {
        this.status = Status.SENT;
        this.sentAt = now;
    }

    void failed(Instant retryAt) {
        this.attempts++;
        if (attempts >= 5) {
            this.status = Status.FAILED;
        } else {
            this.scheduledAt = retryAt;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public String getAddress() {
        return address;
    }

    public Channel getChannel() {
        return channel;
    }

    public Priority getPriority() {
        return priority;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getBody() {
        return body;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
