package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Pregnancy declaration by a crew member (AME / CMO only). */
@Entity
@Table(name = "pregnancy_declaration", schema = "surveillance")
public class PregnancyDeclaration {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "declared_on")
    private LocalDate declaredOn;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "created_at")
    private Instant createdAt;

    public PregnancyDeclaration() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public LocalDate getDeclaredOn() { return declaredOn; }
    public void setDeclaredOn(LocalDate v) { this.declaredOn = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
