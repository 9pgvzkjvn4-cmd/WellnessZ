package com.wellnessz.e01.encounter;

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

/** Walk-in / emergency / first-aid encounter at a clinic or first-aid post (E01-F03). */
@Entity
@Table(name = "encounter", schema = "intake")
public class Encounter {

    public enum Mode { WALK_IN, EMERGENCY }

    public enum Status { OPEN, ENRICHED, CONVERTED, CLOSED }

    @Id
    private UUID id;
    @Column(name = "client_ref", nullable = false, unique = true)
    private UUID clientRef;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mode mode;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "employee_name", length = 200)
    private String employeeName;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "site_code", length = 40)
    private String siteCode;
    @Column(length = 200)
    private String location;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "chief_complaint", columnDefinition = "text")
    private String chiefComplaint;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "action_taken", columnDefinition = "text")
    private String actionTaken;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "notes", columnDefinition = "text")
    private String notes;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;
    @Column(name = "enrichment_due", nullable = false)
    private boolean enrichmentDue;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref", length = 30)
    private String caseRef;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "enriched_at")
    private Instant enrichedAt;

    protected Encounter() {
    }

    public Encounter(UUID clientRef, Mode mode, String employeeId, String employeeName, Instant occurredAt, String siteCode,
                     String location, String chiefComplaint, String actionTaken, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.clientRef = clientRef;
        this.mode = mode;
        this.employeeId = employeeId;
        this.employeeName = employeeName;
        this.occurredAt = occurredAt;
        this.siteCode = siteCode;
        this.location = location;
        this.chiefComplaint = chiefComplaint;
        this.actionTaken = actionTaken;
        this.enrichmentDue = mode == Mode.EMERGENCY;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void enrich(String chiefComplaint, String actionTaken, String notes, Instant now) {
        if (chiefComplaint != null) {
            this.chiefComplaint = chiefComplaint;
        }
        if (actionTaken != null) {
            this.actionTaken = actionTaken;
        }
        if (notes != null) {
            this.notes = notes;
        }
        this.enrichmentDue = false;
        this.enrichedAt = now;
        if (status == Status.OPEN) {
            this.status = Status.ENRICHED;
        }
    }

    public void converted(UUID caseId, String caseRef) {
        this.caseId = caseId;
        this.caseRef = caseRef;
        this.status = Status.CONVERTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClientRef() {
        return clientRef;
    }

    public Mode getMode() {
        return mode;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getLocation() {
        return location;
    }

    public String getChiefComplaint() {
        return chiefComplaint;
    }

    public String getActionTaken() {
        return actionTaken;
    }

    public String getNotes() {
        return notes;
    }

    public Status getStatus() {
        return status;
    }

    public boolean isEnrichmentDue() {
        return enrichmentDue;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getCaseRef() {
        return caseRef;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
