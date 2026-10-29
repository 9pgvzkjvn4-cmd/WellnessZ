package com.wellnessz.e04.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Telehealth consultation (video / secure chat) with an OH clinician or partner specialist (E04-F07). */
@Entity(name = "TxTelehealthSession")
@Table(name = "telehealth_session", schema = "treatment")
public class TelehealthSession {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "scheduled_at")
    private Instant scheduledAt;
    @Column(name = "duration_minutes")
    private int durationMinutes;
    @Column(name = "mode")
    private String mode;
    @Column(name = "status")
    private String status;
    @Column(name = "clinician_username")
    private String clinicianUsername;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "crew_layover")
    private boolean crewLayover;
    @Column(name = "layover_city")
    private String layoverCity;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;
    @Column(name = "partner_read_at")
    private Instant partnerReadAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Column(name = "completed_at")
    private Instant completedAt;

    public TelehealthSession() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant v) { this.scheduledAt = v; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int v) { this.durationMinutes = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getClinicianUsername() { return clinicianUsername; }
    public void setClinicianUsername(String v) { this.clinicianUsername = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public boolean isCrewLayover() { return crewLayover; }
    public void setCrewLayover(boolean v) { this.crewLayover = v; }
    public String getLayoverCity() { return layoverCity; }
    public void setLayoverCity(String v) { this.layoverCity = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID v) { this.encounterId = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { this.cancelReason = v; }
    public Instant getPartnerReadAt() { return partnerReadAt; }
    public void setPartnerReadAt(Instant v) { this.partnerReadAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
}
