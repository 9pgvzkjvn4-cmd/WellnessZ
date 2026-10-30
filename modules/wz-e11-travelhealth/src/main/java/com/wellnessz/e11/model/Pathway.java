package com.wellnessz.e11.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Exposure pathway template (E11-F03) with protocol timers and the serology schedule (JSON). */
@Entity(name = "E11Pathway")
@Table(name = "pathway", schema = "travelhealth")
public class Pathway {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "description")
    private String description;
    @Column(name = "timers", columnDefinition = "text")
    private String timers;
    @Column(name = "serology", columnDefinition = "text")
    private String serology;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String v) {
        this.code = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String v) {
        this.description = v;
    }

    public String getTimers() {
        return timers;
    }

    public void setTimers(String v) {
        this.timers = v;
    }

    public String getSerology() {
        return serology;
    }

    public void setSerology(String v) {
        this.serology = v;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean v) {
        this.active = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }
}
