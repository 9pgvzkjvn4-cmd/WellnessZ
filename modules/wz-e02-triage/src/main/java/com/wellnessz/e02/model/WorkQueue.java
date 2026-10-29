package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Work queue (team queue) with its distribution list; restricted queues hold restricted-class cases only. */
@Entity
@Table(name = "work_queue", schema = "triage")
public class WorkQueue {

    @Id
    @Column(length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(length = 500)
    private String description;
    @Column(name = "required_role", length = 40)
    private String requiredRole;
    @Column(length = 320)
    private String email;
    @Column(nullable = false)
    private boolean restricted;

    public WorkQueue() {
        // JPA; fields are populated through setters by the owning service
    }


    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRequiredRole() {
        return requiredRole;
    }

    public void setRequiredRole(String requiredRole) {
        this.requiredRole = requiredRole;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isRestricted() {
        return restricted;
    }

    public void setRestricted(boolean restricted) {
        this.restricted = restricted;
    }
}
