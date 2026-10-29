package com.wellnessz.masterdata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * External organisation the OH service works with (increment 4): partner hospitals, specialists, physiotherapy, imaging,
 * mental-health and telehealth providers, the travel-assistance partner (E04), insurers, TPAs and reporting authorities
 * (E08). The code is the last segment of a partner portal user's {@code org_scope} (e.g. {@code /PARTNERS/CITYHOSP}).
 */
@Entity
@Table(name = "partner_organisation", schema = "masterdata")
public class PartnerOrganisation {

    @Id
    @Column(length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    /** HOSPITAL, CLINIC, SPECIALIST, PHYSIOTHERAPY, IMAGING, LABORATORY, MENTAL_HEALTH, TELEHEALTH, ASSISTANCE, INSURER, TPA, AUTHORITY. */
    @Column(name = "org_type", nullable = false, length = 30)
    private String orgType;
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;
    @Column(length = 100)
    private String city;
    @Column(name = "contact_email", length = 320)
    private String contactEmail;
    @Column(length = 40)
    private String phone;
    /** The organisation's users may sign in to the partner portal (MFA enforced). */
    @Column(name = "portal_enabled", nullable = false)
    private boolean portalEnabled;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PartnerOrganisation() {
    }

    public PartnerOrganisation(String code, String name, String orgType, String countryCode, String city, String contactEmail, String phone,
                               boolean portalEnabled, boolean active, Instant updatedAt) {
        this.code = code;
        this.name = name;
        this.orgType = orgType;
        this.countryCode = countryCode;
        this.city = city;
        this.contactEmail = contactEmail;
        this.phone = phone;
        this.portalEnabled = portalEnabled;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getOrgType() {
        return orgType;
    }

    public String getCountryCode() {
        return countryCode;
    }

    public String getCity() {
        return city;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isPortalEnabled() {
        return portalEnabled;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
