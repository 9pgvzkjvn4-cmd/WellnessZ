package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Personal saved queue view (E02-BL-009). */
@Entity
@Table(name = "saved_view", schema = "triage")
public class SavedView {

    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String username;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false, length = 30)
    private String queue;
    @Column(nullable = false, length = 2000)
    private String filters;

    public SavedView() {
        // JPA; fields are populated through setters by the owning service
    }


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getQueue() {
        return queue;
    }

    public void setQueue(String queue) {
        this.queue = queue;
    }

    public String getFilters() {
        return filters;
    }

    public void setFilters(String filters) {
        this.filters = filters;
    }
}
