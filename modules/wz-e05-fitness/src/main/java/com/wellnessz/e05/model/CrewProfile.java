package com.wellnessz.e05.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Crew operational attributes (fleet, base) used only for aggregate dashboards. */
@Entity
@Table(name = "crew_profile", schema = "fitness")
public class CrewProfile {

    @Id
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "fleet")
    private String fleet;
    @Column(name = "base")
    private String base;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public CrewProfile() {
        // JPA; populated through setters by the owning service
    }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getFleet() { return fleet; }
    public void setFleet(String v) { this.fleet = v; }
    public String getBase() { return base; }
    public void setBase(String v) { this.base = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
