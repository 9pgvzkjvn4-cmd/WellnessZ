package com.wellnessz.casekernel.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Case ↔ incident / case / claim / surveillance / encounter / absence linkage (E01-F05, E01-F06). */
@Entity
@Table(name = "case_link", schema = "casemgmt")
public class CaseLink {

    public enum Type { INCIDENT, CASE, CLAIM, SURVEILLANCE, ENCOUNTER, ABSENCE, MERGED_FROM, TRIGGER }

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "link_type", nullable = false, length = 20)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Type type;
    @Column(nullable = false, length = 200)
    private String ref;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CaseLink() {
    }

    public CaseLink(UUID caseId, Type type, String ref, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.caseId = caseId;
        this.type = type;
        this.ref = ref;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public Type getType() {
        return type;
    }

    public String getRef() {
        return ref;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void moveTo(UUID targetCaseId) {
        this.caseId = targetCaseId;
    }
}
