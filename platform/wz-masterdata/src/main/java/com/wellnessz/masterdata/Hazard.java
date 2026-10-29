package com.wellnessz.masterdata;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Hazard catalogue entry (noise, cosmic radiation, fatigue, manual handling, biological …). */
@Entity
@Table(name = "hazard", schema = "masterdata")
public class Hazard {

    @Id
    @Column(length = 64)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 60)
    private String category;
    @Column(name = "surveillance_required", nullable = false)
    private boolean surveillanceRequired;
    @Column(name = "surveillance_interval_months")
    private Integer surveillanceIntervalMonths;

    protected Hazard() {
    }

    public Hazard(String code, String name, String category, boolean surveillanceRequired, Integer intervalMonths) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.surveillanceRequired = surveillanceRequired;
        this.surveillanceIntervalMonths = intervalMonths;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public boolean isSurveillanceRequired() {
        return surveillanceRequired;
    }

    public Integer getSurveillanceIntervalMonths() {
        return surveillanceIntervalMonths;
    }
}
