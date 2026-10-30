package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Contact with the employee (urgent call, self-referral, programme referral …); note encrypted, insert-only. */
@Entity(name = "E09SupportContact")
@Table(name = "support_contact", schema = "mentalhealth")
public class SupportContact {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "contact_type")
    private String contactType;
    @Column(name = "outcome")
    private String outcome;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "actor")
    private String actor;
    @Column(name = "occurred_at")
    private Instant occurredAt;

    public SupportContact() {
        // JPA; populated through setters by the owning service
    }

    public static SupportContact create() {
        SupportContact x = new SupportContact();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getContactType() { return contactType; }
    public void setContactType(String v) { this.contactType = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
}
