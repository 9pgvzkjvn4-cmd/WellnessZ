package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Participant booked onto a screening event. */
@Entity(name = "E12ScreeningParticipant")
@Table(name = "screening_participant", schema = "promotion")
public class ScreeningParticipant {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "event_id")
    private UUID eventId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "status")
    private String status;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "appointment_starts_at")
    private Instant appointmentStartsAt;
    @Column(name = "added_by")
    private String addedBy;
    @Column(name = "added_at")
    private Instant addedAt;

    public ScreeningParticipant() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEventId() { return eventId; }
    public void setEventId(UUID v) { this.eventId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
    public Instant getAppointmentStartsAt() { return appointmentStartsAt; }
    public void setAppointmentStartsAt(Instant v) { this.appointmentStartsAt = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant v) { this.addedAt = v; }
}
