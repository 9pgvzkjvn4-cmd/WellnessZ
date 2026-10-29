package com.wellnessz.e04.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Network profile of a partner provider (services, specialties, coverage, preferred flag). */
@Entity(name = "TxProviderProfile")
@Table(name = "provider_profile", schema = "treatment")
public class ProviderProfile {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "specialties")
    private String specialties;
    @Column(name = "services")
    private String services;
    @Column(name = "languages")
    private String languages;
    @Column(name = "accreditation")
    private String accreditation;
    @Column(name = "coverage")
    private String coverage;
    @Column(name = "latitude")
    private BigDecimal latitude;
    @Column(name = "longitude")
    private BigDecimal longitude;
    @Column(name = "preferred")
    private boolean preferred;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ProviderProfile() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getSpecialties() { return specialties; }
    public void setSpecialties(String v) { this.specialties = v; }
    public String getServices() { return services; }
    public void setServices(String v) { this.services = v; }
    public String getLanguages() { return languages; }
    public void setLanguages(String v) { this.languages = v; }
    public String getAccreditation() { return accreditation; }
    public void setAccreditation(String v) { this.accreditation = v; }
    public String getCoverage() { return coverage; }
    public void setCoverage(String v) { this.coverage = v; }
    public BigDecimal getLatitude() { return latitude; }
    public void setLatitude(BigDecimal v) { this.latitude = v; }
    public BigDecimal getLongitude() { return longitude; }
    public void setLongitude(BigDecimal v) { this.longitude = v; }
    public boolean isPreferred() { return preferred; }
    public void setPreferred(boolean v) { this.preferred = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
