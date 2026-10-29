package com.wellnessz.e14.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Appointment type of the service catalogue (E14-F01, BL-010). */
@Entity
@Table(name = "appointment_type", schema = "scheduling")
public class AppointmentType {

    @Id @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "duration_minutes")
    private int durationMinutes;
    @Column(name = "clinician_roles")
    private String clinicianRoles;
    @Column(name = "room_type")
    private String roomType;
    @Column(name = "device_category")
    private String deviceCategory;
    @Column(name = "telehealth")
    private boolean telehealth;
    @Column(name = "self_bookable")
    private boolean selfBookable;
    @Column(name = "charge_code")
    private String chargeCode;
    @Column(name = "internal_price")
    private java.math.BigDecimal internalPrice;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public AppointmentType() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public int getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(int v) { this.durationMinutes = v; }
    public String getClinicianRoles() { return clinicianRoles; }
    public void setClinicianRoles(String v) { this.clinicianRoles = v; }
    public String getRoomType() { return roomType; }
    public void setRoomType(String v) { this.roomType = v; }
    public String getDeviceCategory() { return deviceCategory; }
    public void setDeviceCategory(String v) { this.deviceCategory = v; }
    public boolean isTelehealth() { return telehealth; }
    public void setTelehealth(boolean v) { this.telehealth = v; }
    public boolean isSelfBookable() { return selfBookable; }
    public void setSelfBookable(boolean v) { this.selfBookable = v; }
    public String getChargeCode() { return chargeCode; }
    public void setChargeCode(String v) { this.chargeCode = v; }
    public java.math.BigDecimal getInternalPrice() { return internalPrice; }
    public void setInternalPrice(java.math.BigDecimal v) { this.internalPrice = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
