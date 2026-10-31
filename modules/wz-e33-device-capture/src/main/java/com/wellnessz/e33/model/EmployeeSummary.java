package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Plain-language result summary released to the employee (E33-F12), in the employee's preferred language. */
@Entity(name = "E33EmployeeSummary")
@Table(name = "employee_summary", schema = "devicecap")
public class EmployeeSummary {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "observation_id")
    private UUID observationId;
    @Column(name = "category")
    private String category;
    @Column(name = "measured_at")
    private Instant measuredAt;
    @Column(name = "language")
    private String language;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "plain_language", columnDefinition = "text")
    private String plainLanguage;
    @Column(name = "education_link")
    private String educationLink;
    @Column(name = "released_by")
    private String releasedBy;
    @Column(name = "released_at")
    private Instant releasedAt;

    protected EmployeeSummary() {
        // JPA
    }

    public EmployeeSummary(String employeeId, UUID sessionId, UUID observationId, String category, Instant measuredAt, String language,
                           String plainLanguage, String educationLink, String releasedBy, Instant releasedAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.sessionId = sessionId;
        this.observationId = observationId;
        this.category = category;
        this.measuredAt = measuredAt;
        this.language = language;
        this.plainLanguage = plainLanguage;
        this.educationLink = educationLink;
        this.releasedBy = releasedBy;
        this.releasedAt = releasedAt;
    }

    public UUID getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public UUID getSessionId() { return sessionId; }
    public UUID getObservationId() { return observationId; }
    public String getCategory() { return category; }
    public Instant getMeasuredAt() { return measuredAt; }
    public String getLanguage() { return language; }
    public String getPlainLanguage() { return plainLanguage; }
    public String getEducationLink() { return educationLink; }
    public String getReleasedBy() { return releasedBy; }
    public Instant getReleasedAt() { return releasedAt; }
}
