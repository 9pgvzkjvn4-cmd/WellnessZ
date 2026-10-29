package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** OH contact with an absent employee (early intervention or long-term absence contact schedule). */
@Entity(name = "AbsenceContact")
@Table(name = "contact", schema = "absence")
public class Contact {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "queue_item_id")
    private UUID queueItemId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "contacted_at")
    private Instant contactedAt;
    @Column(name = "channel")
    private String channel;
    @Column(name = "outcome")
    private String outcome;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "contacted_by")
    private String contactedBy;
    @Column(name = "next_contact_due")
    private LocalDate nextContactDue;

    public Contact() {
        // JPA; populated through setters by the owning service
    }

    public static Contact create() {
        Contact x = new Contact();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getQueueItemId() { return queueItemId; }
    public void setQueueItemId(UUID v) { this.queueItemId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public Instant getContactedAt() { return contactedAt; }
    public void setContactedAt(Instant v) { this.contactedAt = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getContactedBy() { return contactedBy; }
    public void setContactedBy(String v) { this.contactedBy = v; }
    public LocalDate getNextContactDue() { return nextContactDue; }
    public void setNextContactDue(LocalDate v) { this.nextContactDue = v; }
}
