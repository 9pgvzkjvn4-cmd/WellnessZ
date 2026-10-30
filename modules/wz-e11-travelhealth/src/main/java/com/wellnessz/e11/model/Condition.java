package com.wellnessz.e11.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Communicable condition (E11-F02): notification deadline, exclusion and monitoring rules. */
@Entity(name = "E11Condition")
@Table(name = "condition", schema = "travelhealth")
public class Condition {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "notifiable")
    private boolean notifiable;
    @Column(name = "report_within_hours")
    private Integer reportWithinHours;
    @Column(name = "exclusion_days")
    private Integer exclusionDays;
    @Column(name = "food_handler_exclusion")
    private boolean foodHandlerExclusion;
    @Column(name = "monitoring_days")
    private int monitoringDays;
    @Column(name = "return_criteria")
    private String returnCriteria;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public String getCode() {
        return code;
    }

    public void setCode(String v) {
        this.code = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public boolean isNotifiable() {
        return notifiable;
    }

    public void setNotifiable(boolean v) {
        this.notifiable = v;
    }

    public Integer getReportWithinHours() {
        return reportWithinHours;
    }

    public void setReportWithinHours(Integer v) {
        this.reportWithinHours = v;
    }

    public Integer getExclusionDays() {
        return exclusionDays;
    }

    public void setExclusionDays(Integer v) {
        this.exclusionDays = v;
    }

    public boolean isFoodHandlerExclusion() {
        return foodHandlerExclusion;
    }

    public void setFoodHandlerExclusion(boolean v) {
        this.foodHandlerExclusion = v;
    }

    public int getMonitoringDays() {
        return monitoringDays;
    }

    public void setMonitoringDays(int v) {
        this.monitoringDays = v;
    }

    public String getReturnCriteria() {
        return returnCriteria;
    }

    public void setReturnCriteria(String v) {
        this.returnCriteria = v;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant v) {
        this.updatedAt = v;
    }
}
