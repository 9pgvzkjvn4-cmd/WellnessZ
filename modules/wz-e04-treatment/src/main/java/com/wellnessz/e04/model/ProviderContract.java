package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Versioned provider contract with rate card and SLA (procurement). */
@Entity(name = "TxProviderContract")
@Table(name = "provider_contract", schema = "treatment")
public class ProviderContract {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "ref")
    private String ref;
    @Column(name = "version")
    private int version;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "rates")
    private String rates;
    @Column(name = "appointment_days")
    private Integer appointmentDays;
    @Column(name = "urgent_hours")
    private Integer urgentHours;
    @Column(name = "report_days")
    private Integer reportDays;
    @Column(name = "current_version")
    private boolean currentVersion;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public ProviderContract() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getRates() { return rates; }
    public void setRates(String v) { this.rates = v; }
    public Integer getAppointmentDays() { return appointmentDays; }
    public void setAppointmentDays(Integer v) { this.appointmentDays = v; }
    public Integer getUrgentHours() { return urgentHours; }
    public void setUrgentHours(Integer v) { this.urgentHours = v; }
    public Integer getReportDays() { return reportDays; }
    public void setReportDays(Integer v) { this.reportDays = v; }
    public boolean isCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(boolean v) { this.currentVersion = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
