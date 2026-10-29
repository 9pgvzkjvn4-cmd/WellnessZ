package com.wellnessz.e14.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Bookable resource of a clinic site: clinician, room or device. */
@Entity
@Table(name = "resource", schema = "scheduling")
public class ClinicResource {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "type")
    private String type;
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "username")
    private String username;
    @Column(name = "room_type")
    private String roomType;
    @Column(name = "equipment_id")
    private UUID equipmentId;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ClinicResource() {
        // JPA; populated through setters by the owning service
    }

    public static ClinicResource create() {
        ClinicResource x = new ClinicResource();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getRoomType() { return roomType; }
    public void setRoomType(String v) { this.roomType = v; }
    public UUID getEquipmentId() { return equipmentId; }
    public void setEquipmentId(UUID v) { this.equipmentId = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
