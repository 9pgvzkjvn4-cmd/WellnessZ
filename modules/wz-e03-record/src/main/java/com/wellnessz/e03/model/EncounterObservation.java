package com.wellnessz.e03.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Body-map entry stored as a coded observation (site, laterality, view, mechanism). */
@Entity
@Table(name = "encounter_observation", schema = "ohrecord")
public class EncounterObservation {

    @Id
    private UUID id;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Column(name = "kind")
    private String kind;
    @Column(name = "code_system")
    private String codeSystem;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "laterality")
    private String laterality;
    @Column(name = "body_view")
    private String bodyView;
    @Column(name = "mechanism_code")
    private String mechanismCode;
    @Column(name = "position")
    private int position;
    @Column(name = "active")
    private boolean active;

    public EncounterObservation() {
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getCodeSystem() {
        return codeSystem;
    }

    public void setCodeSystem(String codeSystem) {
        this.codeSystem = codeSystem;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public String getLaterality() {
        return laterality;
    }

    public void setLaterality(String laterality) {
        this.laterality = laterality;
    }

    public String getBodyView() {
        return bodyView;
    }

    public void setBodyView(String bodyView) {
        this.bodyView = bodyView;
    }

    public String getMechanismCode() {
        return mechanismCode;
    }

    public void setMechanismCode(String mechanismCode) {
        this.mechanismCode = mechanismCode;
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
