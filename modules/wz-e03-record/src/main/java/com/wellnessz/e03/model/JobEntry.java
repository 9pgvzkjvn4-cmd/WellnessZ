package com.wellnessz.e03.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** ODH job history entry (PastOrPresentJob); the current job is synchronised from the HRIS master. */
@Entity
@Table(name = "job_history", schema = "ohrecord")
public class JobEntry {

    @Id
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "job_title")
    private String jobTitle;
    @Column(name = "employer")
    private String employer;
    @Column(name = "industry")
    private String industry;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "is_current")
    private boolean current;
    @Column(name = "source")
    private String source;
    @Column(name = "hazard_codes")
    private String hazardCodes;
    @Column(name = "duties")
    private String duties;
    @Column(name = "weekly_hours")
    private Integer weeklyHours;
    @Column(name = "recorded_by")
    private String recordedBy;
    @Column(name = "recorded_at")
    private Instant recordedAt;

    public JobEntry() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getJobCode() {
        return jobCode;
    }

    public void setJobCode(String jobCode) {
        this.jobCode = jobCode;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public void setJobTitle(String jobTitle) {
        this.jobTitle = jobTitle;
    }

    public String getEmployer() {
        return employer;
    }

    public void setEmployer(String employer) {
        this.employer = employer;
    }

    public String getIndustry() {
        return industry;
    }

    public void setIndustry(String industry) {
        this.industry = industry;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public boolean isCurrent() {
        return current;
    }

    public void setCurrent(boolean current) {
        this.current = current;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getHazardCodes() {
        return hazardCodes;
    }

    public void setHazardCodes(String hazardCodes) {
        this.hazardCodes = hazardCodes;
    }

    public String getDuties() {
        return duties;
    }

    public void setDuties(String duties) {
        this.duties = duties;
    }

    public Integer getWeeklyHours() {
        return weeklyHours;
    }

    public void setWeeklyHours(Integer weeklyHours) {
        this.weeklyHours = weeklyHours;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(Instant recordedAt) {
        this.recordedAt = recordedAt;
    }
}
