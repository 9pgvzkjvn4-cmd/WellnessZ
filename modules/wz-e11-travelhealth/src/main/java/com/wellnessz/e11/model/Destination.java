package com.wellnessz.e11.model;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Destination risk content (E11-F01): in-house or licensed source; lists are JSON arrays. */
@Entity(name = "E11Destination")
@Table(name = "destination", schema = "travelhealth")
public class Destination {

    @Id
    @Column(name = "country_code")
    private String countryCode;
    @Column(name = "name")
    private String name;
    @Column(name = "station_codes")
    private String stationCodes;
    @Column(name = "risks", columnDefinition = "text")
    private String risks;
    @Column(name = "recommended_vaccines", columnDefinition = "text")
    private String recommendedVaccines;
    @Column(name = "prophylaxis", columnDefinition = "text")
    private String prophylaxis;
    @Column(name = "advice", columnDefinition = "text")
    private String advice;
    @Column(name = "source")
    private String source;
    @Column(name = "reviewed_on")
    private LocalDate reviewedOn;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    public String getCountryCode() {
        return countryCode;
    }

    public void setCountryCode(String v) {
        this.countryCode = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getStationCodes() {
        return stationCodes;
    }

    public void setStationCodes(String v) {
        this.stationCodes = v;
    }

    public String getRisks() {
        return risks;
    }

    public void setRisks(String v) {
        this.risks = v;
    }

    public String getRecommendedVaccines() {
        return recommendedVaccines;
    }

    public void setRecommendedVaccines(String v) {
        this.recommendedVaccines = v;
    }

    public String getProphylaxis() {
        return prophylaxis;
    }

    public void setProphylaxis(String v) {
        this.prophylaxis = v;
    }

    public String getAdvice() {
        return advice;
    }

    public void setAdvice(String v) {
        this.advice = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v;
    }

    public LocalDate getReviewedOn() {
        return reviewedOn;
    }

    public void setReviewedOn(LocalDate v) {
        this.reviewedOn = v;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String v) {
        this.updatedBy = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }

    public long getVersion() {
        return version;
    }
}
