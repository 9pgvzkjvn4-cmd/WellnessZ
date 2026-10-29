package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Person attributes not held by the HRIS master (date of birth for age-based frequencies, licences). */
@Entity
@Table(name = "employee_profile", schema = "surveillance")
public class EmployeeProfile {

    @Id
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;
    @Column(name = "licence_types")
    private String licenceTypes;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public EmployeeProfile() {
        // JPA; populated through setters by the owning service
    }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate v) { this.dateOfBirth = v; }
    public String getLicenceTypes() { return licenceTypes; }
    public void setLicenceTypes(String v) { this.licenceTypes = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
