package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Pseudonymous contact request – no employee identifier; callback phone encrypted and purged after the retention period. */
@Entity(name = "E09PeerContactRequest")
@Table(name = "peer_contact_request", schema = "mentalhealth")
public class PeerContactRequest {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "contact_code")
    private String contactCode;
    @Column(name = "alias")
    private String alias;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "callback_phone", columnDefinition = "text")
    private String callbackPhone;
    @Column(name = "base")
    private String base;
    @Column(name = "language")
    private String language;
    @Column(name = "volunteer_username")
    private String volunteerUsername;
    @Column(name = "status")
    private String status;
    @Column(name = "requested_at")
    private Instant requestedAt;
    @Column(name = "phone_purged_at")
    private Instant phonePurgedAt;

    public PeerContactRequest() {
        // JPA; populated through setters by the owning service
    }

    public static PeerContactRequest create() {
        PeerContactRequest x = new PeerContactRequest();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getContactCode() { return contactCode; }
    public void setContactCode(String v) { this.contactCode = v; }
    public String getAlias() { return alias; }
    public void setAlias(String v) { this.alias = v; }
    public String getCallbackPhone() { return callbackPhone; }
    public void setCallbackPhone(String v) { this.callbackPhone = v; }
    public String getBase() { return base; }
    public void setBase(String v) { this.base = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public String getVolunteerUsername() { return volunteerUsername; }
    public void setVolunteerUsername(String v) { this.volunteerUsername = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Instant getPhonePurgedAt() { return phonePurgedAt; }
    public void setPhonePurgedAt(Instant v) { this.phonePurgedAt = v; }
}
