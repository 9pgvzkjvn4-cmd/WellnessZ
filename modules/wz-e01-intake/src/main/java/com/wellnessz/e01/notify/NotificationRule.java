package com.wellnessz.e01.notify;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.notification.Channel;

/** One row of the notification matrix (who, what, when, channel) per intake source and case type (E01-BL-025). */
@Entity
@Table(name = "notification_rule", schema = "intake")
public class NotificationRule {

    public enum Event { CASE_OPENED, CASE_ASSIGNED, SLA_AT_RISK, SLA_BREACHED, ESCALATED }

    public enum Recipient { OH_QUEUE, CASE_OWNER, HSE_REPORTER, LINE_MANAGER, CREW_SCHEDULING, NURSING_HEAD, EMPLOYEE, HYGIENE_MANAGER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Event event;
    @Column(length = 40)
    private String source;
    @Column(name = "case_type", length = 40)
    private String caseType;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Recipient recipient;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel;
    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;
    @Column(nullable = false)
    private boolean active = true;

    protected NotificationRule() {
    }

    public NotificationRule(Event event, String source, String caseType, Recipient recipient, Channel channel, String templateKey,
                            boolean active) {
        this.event = event;
        this.source = source;
        this.caseType = caseType;
        this.recipient = recipient;
        this.channel = channel;
        this.templateKey = templateKey;
        this.active = active;
    }

    public boolean appliesTo(Event e, String caseSource, String type) {
        return active && event == e && (source == null || source.equals(caseSource)) && (caseType == null || caseType.equals(type));
    }

    public Long getId() {
        return id;
    }

    public Event getEvent() {
        return event;
    }

    public String getSource() {
        return source;
    }

    public String getCaseType() {
        return caseType;
    }

    public Recipient getRecipient() {
        return recipient;
    }

    public Channel getChannel() {
        return channel;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public boolean isActive() {
        return active;
    }
}
