package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Verification call to a certificate issuer. */
@Entity(name = "AbsenceCertificateCall")
@Table(name = "certificate_call", schema = "absence")
public class CertificateCall {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "certificate_id")
    private UUID certificateId;
    @Column(name = "called_party")
    private String calledParty;
    @Column(name = "outcome")
    private String outcome;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "called_by")
    private String calledBy;
    @Column(name = "called_at")
    private Instant calledAt;

    public CertificateCall() {
        // JPA; populated through setters by the owning service
    }

    public static CertificateCall create() {
        CertificateCall x = new CertificateCall();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCertificateId() { return certificateId; }
    public void setCertificateId(UUID v) { this.certificateId = v; }
    public String getCalledParty() { return calledParty; }
    public void setCalledParty(String v) { this.calledParty = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getCalledBy() { return calledBy; }
    public void setCalledBy(String v) { this.calledBy = v; }
    public Instant getCalledAt() { return calledAt; }
    public void setCalledAt(Instant v) { this.calledAt = v; }
}
