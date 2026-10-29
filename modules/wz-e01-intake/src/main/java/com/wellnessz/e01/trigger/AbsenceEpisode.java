package com.wellnessz.e01.trigger;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Absence episode from the time & attendance / crew sickness feed (non-clinical reason category only). */
@Entity
@Table(name = "absence_episode", schema = "intake")
public class AbsenceEpisode {

    @Id
    private UUID id;
    @Column(name = "source_ref", nullable = false, unique = true, length = 100)
    private String sourceRef;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "reason_category", length = 40)
    private String reasonCategory;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected AbsenceEpisode() {
    }

    public AbsenceEpisode(String sourceRef, String employeeId, LocalDate startDate, LocalDate endDate, String reasonCategory, Instant now) {
        this.id = UUID.randomUUID();
        this.sourceRef = sourceRef;
        this.employeeId = employeeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.reasonCategory = reasonCategory;
        this.receivedAt = now;
    }

    public void update(LocalDate endDate) {
        this.endDate = endDate;
    }

    /** Calendar days; open-ended episodes count up to {@code today}. */
    public long days(LocalDate today) {
        LocalDate end = endDate == null ? today : endDate;
        return ChronoUnit.DAYS.between(startDate, end) + 1;
    }

    public UUID getId() {
        return id;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public String getReasonCategory() {
        return reasonCategory;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
