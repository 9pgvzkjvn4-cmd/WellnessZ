package com.wellnessz.masterdata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Occupational Health clinic site (E14): HQ medical centre, airside first-aid posts, catering medical room, cargo /
 * engineering clinics and outstations. Shared by scheduling (E14), surveillance campaigns (E07) and absence contacts (E06).
 */
@Entity
@Table(name = "clinic_site", schema = "masterdata")
public class ClinicSite {

    @Id
    @Column(length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    /** HQ_MEDICAL_CENTRE, AIRSIDE_POST, CATERING_ROOM, CARGO_ENG_CLINIC, OUTSTATION, MOBILE. */
    @Column(name = "site_type", nullable = false, length = 30)
    private String siteType;
    /** HRIS location code served by the site (employee {@code locationCode}). */
    @Column(name = "location_code", nullable = false, length = 40)
    private String locationCode;
    @Column(name = "time_zone", nullable = false, length = 60)
    private String timeZone;
    @Column(length = 500)
    private String address;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ClinicSite() {
    }

    public ClinicSite(String code, String name, String siteType, String locationCode, String timeZone, String address, boolean active,
                      Instant updatedAt) {
        this.code = code;
        this.name = name;
        this.siteType = siteType;
        this.locationCode = locationCode;
        this.timeZone = timeZone;
        this.address = address;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getSiteType() {
        return siteType;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public String getAddress() {
        return address;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
