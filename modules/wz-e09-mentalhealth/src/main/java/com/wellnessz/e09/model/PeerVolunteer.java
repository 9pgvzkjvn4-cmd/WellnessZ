package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Peer-support volunteer of the crew support programme (register, availability). */
@Entity(name = "E09PeerVolunteer")
@Table(name = "peer_volunteer", schema = "mentalhealth")
public class PeerVolunteer {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "username")
    private String username;
    @Column(name = "display_name")
    private String displayName;
    @Column(name = "email")
    private String email;
    @Column(name = "base")
    private String base;
    @Column(name = "languages")
    private String languages;
    @Column(name = "available")
    private boolean available;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private Long version;

    public PeerVolunteer() {
        // JPA; populated through setters by the owning service
    }

    public static PeerVolunteer create() {
        PeerVolunteer x = new PeerVolunteer();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String v) { this.displayName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getBase() { return base; }
    public void setBase(String v) { this.base = v; }
    public String getLanguages() { return languages; }
    public void setLanguages(String v) { this.languages = v; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean v) { this.available = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public Long getVersion() { return version; }
}
