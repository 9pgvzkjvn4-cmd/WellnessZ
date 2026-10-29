package com.wellnessz.masterdata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Employee master record cached from HRIS / HCM (E15 inbound, E18 master data). Source of the mandatory case
 * identity fields (E01-HLR-05): HRIS id, subsidiary, department, cost centre, job code, location, shift.
 * Contains personal (not health) data – classification CONFIDENTIAL.
 */
@Entity
@Table(name = "employee", schema = "masterdata")
public class Employee {

    @Id
    @Column(name = "employee_id", length = 64)
    private String employeeId;
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;
    @Column(length = 320)
    private String email;
    @Column(length = 40)
    private String phone;
    @Column(name = "subsidiary_code", nullable = false, length = 20)
    private String subsidiaryCode;
    @Column(name = "department_code", nullable = false, length = 64)
    private String departmentCode;
    @Column(name = "cost_centre", length = 40)
    private String costCentre;
    @Column(name = "job_code", length = 64)
    private String jobCode;
    @Column(name = "location_code", length = 40)
    private String locationCode;
    @Column(name = "shift_pattern", length = 40)
    private String shiftPattern;
    @Column(name = "preferred_language", length = 8)
    private String preferredLanguage;
    @Column(name = "badge_id", length = 64, unique = true)
    private String badgeId;
    @Column(name = "manager_employee_id", length = 64)
    private String managerEmployeeId;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Employee() {
    }

    public Employee(String employeeId, String displayName, String subsidiaryCode, String departmentCode, Instant now) {
        this.employeeId = employeeId;
        this.displayName = displayName;
        this.subsidiaryCode = subsidiaryCode;
        this.departmentCode = departmentCode;
        this.updatedAt = now;
    }

    /** Applies an HRIS snapshot (full replace of mutable attributes). */
    public void apply(String displayName, String email, String phone, String subsidiaryCode, String departmentCode,
                      String costCentre, String jobCode, String locationCode, String shiftPattern, String preferredLanguage,
                      String badgeId, String managerEmployeeId, boolean active, Instant now) {
        this.displayName = displayName;
        this.email = email;
        this.phone = phone;
        this.subsidiaryCode = subsidiaryCode;
        this.departmentCode = departmentCode;
        this.costCentre = costCentre;
        this.jobCode = jobCode;
        this.locationCode = locationCode;
        this.shiftPattern = shiftPattern;
        this.preferredLanguage = preferredLanguage;
        this.badgeId = badgeId;
        this.managerEmployeeId = managerEmployeeId;
        this.active = active;
        this.updatedAt = now;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getSubsidiaryCode() {
        return subsidiaryCode;
    }

    public String getDepartmentCode() {
        return departmentCode;
    }

    public String getCostCentre() {
        return costCentre;
    }

    public String getJobCode() {
        return jobCode;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getShiftPattern() {
        return shiftPattern;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public String getBadgeId() {
        return badgeId;
    }

    public String getManagerEmployeeId() {
        return managerEmployeeId;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
