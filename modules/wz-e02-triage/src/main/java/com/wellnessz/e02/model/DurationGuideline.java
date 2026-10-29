package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Disability-duration reference (in-house table; licensed guideline content pluggable, E02-BL-014). */
@Entity
@Table(name = "duration_guideline", schema = "triage")
public class DurationGuideline {

    @Id
    @Column(name = "icd_code", length = 10)
    private String icdCode;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "expected_days", nullable = false)
    private int expectedDays;
    @Column(nullable = false, length = 100)
    private String source;

    public DurationGuideline() {
        // JPA; fields are populated through setters by the owning service
    }


    public String getIcdCode() {
        return icdCode;
    }

    public void setIcdCode(String icdCode) {
        this.icdCode = icdCode;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getExpectedDays() {
        return expectedDays;
    }

    public void setExpectedDays(int expectedDays) {
        this.expectedDays = expectedDays;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
