package com.wellnessz.ehsintake.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** EHS incident as received from the incident-management system (primary trigger feed). */
@Entity
@Table(name = "ehs_incident", schema = "intake")
public class EhsIncident {

    public enum Status { RECEIVED, DUPLICATE }

    @Id
    private UUID id;
    @Column(name = "source_system", nullable = false, length = 100)
    private String sourceSystem;
    @Column(name = "external_ref", nullable = false, length = 100)
    private String externalRef;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @Column(length = 200)
    private String location;
    @Column(name = "employee_id", length = 64)
    private String employeeId;
    @Column(name = "employee_name", length = 200)
    private String employeeName;
    @Column(name = "org_unit_code", length = 64)
    private String orgUnitCode;
    @Column(name = "incident_type", length = 100)
    private String incidentType;
    @Column(length = 20)
    private String severity;
    @Column(nullable = false)
    private boolean injury;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(columnDefinition = "text")
    private String description;
    @Column(name = "incident_class", length = 40)
    private String incidentClass;
    @Column(name = "body_part", length = 60)
    private String bodyPart;
    @Column(name = "lost_time")
    private Boolean lostTime;
    @Column(name = "exposure_type", length = 60)
    private String exposureType;
    @Column(name = "unit_code", length = 20)
    private String unitCode;
    @Column(length = 40)
    private String shift;
    @Column(name = "reporter_email", length = 320)
    private String reporterEmail;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntakeChannel channel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "batch_id")
    private UUID batchId;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected EhsIncident() {
    }

    public EhsIncident(IncidentRecord r, IntakeChannel channel, UUID batchId, Instant now) {
        this.id = UUID.randomUUID();
        this.sourceSystem = r.sourceSystem();
        this.externalRef = r.externalRef();
        this.occurredAt = r.occurredAt();
        this.location = r.location();
        this.employeeId = r.employeeId();
        this.employeeName = r.employeeName();
        this.orgUnitCode = r.orgUnitCode();
        this.incidentType = r.incidentType();
        this.severity = r.severity();
        this.injury = r.injury();
        this.description = r.description();
        this.incidentClass = r.incidentClass();
        this.bodyPart = r.bodyPart();
        this.lostTime = r.lostTime();
        this.exposureType = r.exposureType();
        this.unitCode = r.unitCode();
        this.shift = r.shift();
        this.reporterEmail = r.reporterEmail();
        this.channel = channel;
        this.batchId = batchId;
        this.status = Status.RECEIVED;
        this.receivedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getLocation() {
        return location;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public String getOrgUnitCode() {
        return orgUnitCode;
    }

    public String getIncidentType() {
        return incidentType;
    }

    public String getSeverity() {
        return severity;
    }

    public boolean isInjury() {
        return injury;
    }

    public String getDescription() {
        return description;
    }

    public String getIncidentClass() {
        return incidentClass;
    }

    public String getBodyPart() {
        return bodyPart;
    }

    public Boolean getLostTime() {
        return lostTime;
    }

    public String getExposureType() {
        return exposureType;
    }

    public String getUnitCode() {
        return unitCode;
    }

    public String getShift() {
        return shift;
    }

    public String getReporterEmail() {
        return reporterEmail;
    }

    public IntakeChannel getChannel() {
        return channel;
    }

    public Status getStatus() {
        return status;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
