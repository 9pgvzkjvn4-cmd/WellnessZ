package com.wellnessz.e03.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Coded diagnosis on an encounter. */
@Entity
@Table(name = "encounter_diagnosis", schema = "ohrecord")
public class EncounterDiagnosis {

    @Id
    private UUID id;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Column(name = "code_system")
    private String codeSystem;
    @Column(name = "code")
    private String code;
    @Column(name = "code_display")
    private String codeDisplay;
    @Column(name = "is_primary")
    private boolean primary;
    @Column(name = "position")
    private int position;
    @Column(name = "active")
    private boolean active;

    public EncounterDiagnosis() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getEncounterId() {
        return encounterId;
    }

    public void setEncounterId(UUID encounterId) {
        this.encounterId = encounterId;
    }

    public String getCodeSystem() {
        return codeSystem;
    }

    public void setCodeSystem(String codeSystem) {
        this.codeSystem = codeSystem;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCodeDisplay() {
        return codeDisplay;
    }

    public void setCodeDisplay(String codeDisplay) {
        this.codeDisplay = codeDisplay;
    }

    public boolean isPrimary() {
        return primary;
    }

    public void setPrimary(boolean primary) {
        this.primary = primary;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
