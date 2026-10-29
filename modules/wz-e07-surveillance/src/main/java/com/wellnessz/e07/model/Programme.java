package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Versioned surveillance programme (E07-F01): eligibility and panel are JSON documents. */
@Entity
@Table(name = "programme", schema = "surveillance")
public class Programme {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "code")
    private String code;
    @Column(name = "version")
    private int version;
    @Column(name = "title")
    private String title;
    @Column(name = "status")
    private String status;
    @Column(name = "effective_from")
    private LocalDate effectiveFrom;
    @Column(name = "eligibility", columnDefinition = "text")
    private String eligibility;
    @Column(name = "panel", columnDefinition = "text")
    private String panel;
    @Column(name = "frequency_months")
    private int frequencyMonths;
    @Column(name = "frequency_months_over40")
    private Integer frequencyMonthsOver40;
    @Column(name = "standard_code")
    private String standardCode;
    @Column(name = "responsible_role")
    private String responsibleRole;
    @Column(name = "service_code")
    private String serviceCode;
    @Column(name = "regulatory_basis")
    private String regulatoryBasis;
    @Column(name = "approved_by")
    private String approvedBy;
    @Column(name = "approved_at")
    private Instant approvedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public Programme() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(LocalDate v) { this.effectiveFrom = v; }
    public String getEligibility() { return eligibility; }
    public void setEligibility(String v) { this.eligibility = v; }
    public String getPanel() { return panel; }
    public void setPanel(String v) { this.panel = v; }
    public int getFrequencyMonths() { return frequencyMonths; }
    public void setFrequencyMonths(int v) { this.frequencyMonths = v; }
    public Integer getFrequencyMonthsOver40() { return frequencyMonthsOver40; }
    public void setFrequencyMonthsOver40(Integer v) { this.frequencyMonthsOver40 = v; }
    public String getStandardCode() { return standardCode; }
    public void setStandardCode(String v) { this.standardCode = v; }
    public String getResponsibleRole() { return responsibleRole; }
    public void setResponsibleRole(String v) { this.responsibleRole = v; }
    public String getServiceCode() { return serviceCode; }
    public void setServiceCode(String v) { this.serviceCode = v; }
    public String getRegulatoryBasis() { return regulatoryBasis; }
    public void setRegulatoryBasis(String v) { this.regulatoryBasis = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { this.approvedBy = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
