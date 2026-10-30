package com.wellnessz.e09.model;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Training record of a peer volunteer. */
@Entity(name = "E09PeerTraining")
@Table(name = "peer_training", schema = "mentalhealth")
public class PeerTraining {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "volunteer_id")
    private UUID volunteerId;
    @Column(name = "course")
    private String course;
    @Column(name = "completed_on")
    private LocalDate completedOn;
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    public PeerTraining() {
        // JPA; populated through setters by the owning service
    }

    public static PeerTraining create() {
        PeerTraining x = new PeerTraining();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getVolunteerId() { return volunteerId; }
    public void setVolunteerId(UUID v) { this.volunteerId = v; }
    public String getCourse() { return course; }
    public void setCourse(String v) { this.course = v; }
    public LocalDate getCompletedOn() { return completedOn; }
    public void setCompletedOn(LocalDate v) { this.completedOn = v; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate v) { this.expiresOn = v; }
}
