package com.wellnessz.masterdata;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * OH team member directory (E02-F03): roles, sites, languages and specialties used by assignment rules,
 * caseload caps, and out-of-office cover. The username matches the IdP {@code preferred_username}.
 */
@Entity
@Table(name = "staff_member", schema = "masterdata")
public class StaffMember {

    @Id
    @Column(length = 200)
    private String username;
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;
    @Column(length = 320)
    private String email;
    @Column(nullable = false, length = 500)
    private String roles;
    @Column(name = "site_codes", length = 500)
    private String siteCodes;
    @Column(length = 100)
    private String languages;
    @Column(length = 500)
    private String specialties;
    @Column(name = "caseload_cap", nullable = false)
    private int caseloadCap;
    @Column(name = "out_of_office_until")
    private LocalDate outOfOfficeUntil;
    @Column(name = "cover_username", length = 200)
    private String coverUsername;
    @Column(nullable = false)
    private boolean active = true;

    protected StaffMember() {
    }

    public StaffMember(String username, String displayName, String email, String roles, String siteCodes, String languages,
                       String specialties, int caseloadCap) {
        this.username = username;
        this.displayName = displayName;
        this.email = email;
        this.roles = roles;
        this.siteCodes = siteCodes;
        this.languages = languages;
        this.specialties = specialties;
        this.caseloadCap = caseloadCap;
    }

    public boolean isAvailableOn(LocalDate day) {
        return active && (outOfOfficeUntil == null || outOfOfficeUntil.isBefore(day));
    }

    public boolean hasRole(String role) {
        return list(roles).contains(role);
    }

    public void setAvailability(LocalDate outOfOfficeUntil, String coverUsername) {
        this.outOfOfficeUntil = outOfOfficeUntil;
        this.coverUsername = coverUsername;
    }

    public static List<String> list(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getEmail() {
        return email;
    }

    public String getRoles() {
        return roles;
    }

    public String getSiteCodes() {
        return siteCodes;
    }

    public String getLanguages() {
        return languages;
    }

    public String getSpecialties() {
        return specialties;
    }

    public int getCaseloadCap() {
        return caseloadCap;
    }

    public LocalDate getOutOfOfficeUntil() {
        return outOfOfficeUntil;
    }

    public String getCoverUsername() {
        return coverUsername;
    }

    public boolean isActive() {
        return active;
    }
}
