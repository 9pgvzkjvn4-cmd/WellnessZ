package com.wellnessz.e11.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Destination / base health advisory (E11-F05). */
@Entity(name = "E11Advisory")
@Table(name = "advisory", schema = "travelhealth")
public class Advisory {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "title")
    private String title;
    @Column(name = "destination_code")
    private String destinationCode;
    @Column(name = "base_code")
    private String baseCode;
    @Column(name = "roles")
    private String roles;
    @Column(name = "severity")
    private String severity;
    @Column(name = "body")
    private String body;
    @Column(name = "valid_from")
    private LocalDate validFrom;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "published_by")
    private String publishedBy;
    @Column(name = "published_at")
    private Instant publishedAt;

    /** New instance with a random id. */
    public static Advisory create() {
        Advisory e = new Advisory();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String v) {
        this.title = v;
    }

    public String getDestinationCode() {
        return destinationCode;
    }

    public void setDestinationCode(String v) {
        this.destinationCode = v;
    }

    public String getBaseCode() {
        return baseCode;
    }

    public void setBaseCode(String v) {
        this.baseCode = v;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String v) {
        this.roles = v;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String v) {
        this.severity = v;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String v) {
        this.body = v;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public void setValidFrom(LocalDate v) {
        this.validFrom = v;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public void setValidUntil(LocalDate v) {
        this.validUntil = v;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void setPublishedBy(String v) {
        this.publishedBy = v;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant v) {
        this.publishedAt = v;
    }
}
