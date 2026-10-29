package com.wellnessz.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Job / role code with its safety-critical flag and hazard profile, driving surveillance (E07) and fitness rules (E05). */
@Entity
@Table(name = "job_code", schema = "masterdata")
public class JobCode {

    @Id
    @Column(length = 64)
    private String code;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "safety_critical", nullable = false)
    private boolean safetyCritical;
    @Column(name = "hazard_codes", length = 1000)
    private String hazardCodes;

    protected JobCode() {
    }

    public JobCode(String code, String title, boolean safetyCritical, String hazardCodes) {
        this.code = code;
        this.title = title;
        this.safetyCritical = safetyCritical;
        this.hazardCodes = hazardCodes;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public boolean isSafetyCritical() {
        return safetyCritical;
    }

    public String getHazardCodes() {
        return hazardCodes;
    }
}
