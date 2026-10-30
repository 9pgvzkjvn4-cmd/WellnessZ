package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Critical-incident stress management event (from the incident feed or created manually). */
@Entity(name = "E09CismEvent")
@Table(name = "cism_event", schema = "mentalhealth")
public class CismEvent {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "title")
    private String title;
    @Column(name = "category")
    private String category;
    @Column(name = "occurred_at")
    private Instant occurredAt;
    @Column(name = "location")
    private String location;
    @Column(name = "duty_refs")
    private String dutyRefs;
    @Column(name = "source")
    private String source;
    @Column(name = "incident_id")
    private String incidentId;
    @Column(name = "offer_due_by")
    private Instant offerDueBy;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public CismEvent() {
        // JPA; populated through setters by the owning service
    }

    public static CismEvent create() {
        CismEvent x = new CismEvent();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getDutyRefs() { return dutyRefs; }
    public void setDutyRefs(String v) { this.dutyRefs = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getIncidentId() { return incidentId; }
    public void setIncidentId(String v) { this.incidentId = v; }
    public Instant getOfferDueBy() { return offerDueBy; }
    public void setOfferDueBy(Instant v) { this.offerDueBy = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
