package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Weekly diary template: a recurring clinician session at a site (local site time). */
@Entity
@Table(name = "diary_template", schema = "scheduling")
public class DiaryTemplate {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "weekday")
    private int weekday;
    @Column(name = "start_time")
    private LocalTime startTime;
    @Column(name = "end_time")
    private LocalTime endTime;
    @Column(name = "clinician_username")
    private String clinicianUsername;
    @Column(name = "room_code")
    private String roomCode;
    @Column(name = "appointment_types")
    private String appointmentTypes;
    @Column(name = "telehealth")
    private boolean telehealth;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public DiaryTemplate() {
        // JPA; populated through setters by the owning service
    }

    public static DiaryTemplate create() {
        DiaryTemplate x = new DiaryTemplate();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public int getWeekday() { return weekday; }
    public void setWeekday(int v) { this.weekday = v; }
    public LocalTime getStartTime() { return startTime; }
    public void setStartTime(LocalTime v) { this.startTime = v; }
    public LocalTime getEndTime() { return endTime; }
    public void setEndTime(LocalTime v) { this.endTime = v; }
    public String getClinicianUsername() { return clinicianUsername; }
    public void setClinicianUsername(String v) { this.clinicianUsername = v; }
    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String v) { this.roomCode = v; }
    public String getAppointmentTypes() { return appointmentTypes; }
    public void setAppointmentTypes(String v) { this.appointmentTypes = v; }
    public boolean isTelehealth() { return telehealth; }
    public void setTelehealth(boolean v) { this.telehealth = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
