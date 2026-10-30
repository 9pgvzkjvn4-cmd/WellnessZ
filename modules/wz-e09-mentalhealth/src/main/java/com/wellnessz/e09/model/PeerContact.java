package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Peer-support contact log entry – pseudonymous contact code only, never names or employee ids. */
@Entity(name = "E09PeerContact")
@Table(name = "peer_contact", schema = "mentalhealth")
public class PeerContact {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "contact_code")
    private String contactCode;
    @Column(name = "volunteer_username")
    private String volunteerUsername;
    @Column(name = "contact_date")
    private LocalDate contactDate;
    @Column(name = "duration_minutes")
    private int durationMinutes;
    @Column(name = "category")
    private String category;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "base")
    private String base;
    @Column(name = "referred_at")
    private Instant referredAt;
    @Column(name = "created_at")
    private Instant createdAt;

    public PeerContact() {
        // JPA; populated through setters by the owning service
    }

    public static PeerContact create() {
        PeerContact x = new PeerContact();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getContactCode() { return contactCode; }
    public void setContactCode(String v) { this.contactCode = v; }
    public String getVolunteerUsername() { return volunteerUsername; }
    public void setVolunteerUsername(String v) { this.volunteerUsername = v; }
    public LocalDate getContactDate() { return contactDate; }
    public void setContactDate(LocalDate v) { this.contactDate = v; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int v) { this.durationMinutes = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getBase() { return base; }
    public void setBase(String v) { this.base = v; }
    public Instant getReferredAt() { return referredAt; }
    public void setReferredAt(Instant v) { this.referredAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
