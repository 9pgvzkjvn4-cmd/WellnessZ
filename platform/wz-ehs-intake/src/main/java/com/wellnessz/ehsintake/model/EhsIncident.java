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
