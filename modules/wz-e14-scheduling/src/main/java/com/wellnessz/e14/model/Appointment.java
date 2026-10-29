package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Clinic appointment. Carries the appointment type, times, resources and identifiers only – never clinical content. */
@Entity
@Table(name = "appointment", schema = "scheduling")
public class Appointment {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "appointment_type")
    private String appointmentType;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "starts_at")
    private Instant startsAt;
    @Column(name = "ends_at")
    private Instant endsAt;
    @Column(name = "status")
    private String status;
    @Column(name = "clinician_username")
    private String clinicianUsername;
    @Column(name = "room_code")
    private String roomCode;
    @Column(name = "device_id")
    private String deviceId;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "source_type")
    private String sourceType;
    @Column(name = "source_ref")
    private String sourceRef;
    @Column(name = "admin_note")
    private String adminNote;
    @Column(name = "telehealth")
    private boolean telehealth;
    @Column(name = "telehealth_room")
    private String telehealthRoom;
    @Column(name = "ticket")
    private String ticket;
    @Column(name = "checked_in_at")
    private Instant checkedInAt;
    @Column(name = "called_at")
    private Instant calledAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "cancel_reason")
    private String cancelReason;
    @Column(name = "reminder48_sent_at")
    private Instant reminder48SentAt;
    @Column(name = "reminder2_sent_at")
    private Instant reminder2SentAt;
    @Column(name = "booked_by")
    private String bookedBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version @Column(name = "version")
    private long version;

    public Appointment() {
        // JPA; populated through setters by the owning service
    }

    public static Appointment create() {
        Appointment x = new Appointment();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getAppointmentType() { return appointmentType; }
    public void setAppointmentType(String v) { this.appointmentType = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant v) { this.startsAt = v; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant v) { this.endsAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getClinicianUsername() { return clinicianUsername; }
    public void setClinicianUsername(String v) { this.clinicianUsername = v; }
    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String v) { this.roomCode = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { this.sourceType = v; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String v) { this.sourceRef = v; }
    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String v) { this.adminNote = v; }
    public boolean isTelehealth() { return telehealth; }
    public void setTelehealth(boolean v) { this.telehealth = v; }
    public String getTelehealthRoom() { return telehealthRoom; }
    public void setTelehealthRoom(String v) { this.telehealthRoom = v; }
    public String getTicket() { return ticket; }
    public void setTicket(String v) { this.ticket = v; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(Instant v) { this.checkedInAt = v; }
    public Instant getCalledAt() { return calledAt; }
    public void setCalledAt(Instant v) { this.calledAt = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant v) { this.cancelledAt = v; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String v) { this.cancelReason = v; }
    public Instant getReminder48SentAt() { return reminder48SentAt; }
    public void setReminder48SentAt(Instant v) { this.reminder48SentAt = v; }
    public Instant getReminder2SentAt() { return reminder2SentAt; }
    public void setReminder2SentAt(Instant v) { this.reminder2SentAt = v; }
    public String getBookedBy() { return bookedBy; }
    public void setBookedBy(String v) { this.bookedBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
}
