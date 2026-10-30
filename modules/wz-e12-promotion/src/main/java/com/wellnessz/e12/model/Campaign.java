package com.wellnessz.e12.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Health-promotion campaign (E12-F01). */
@Entity(name = "E12Campaign")
@Table(name = "campaign", schema = "promotion")
public class Campaign {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "campaign_ref")
    private String campaignRef;
    @Column(name = "title")
    private String title;
    @Column(name = "risk")
    private String risk;
    @Column(name = "objective")
    private String objective;
    @Column(name = "target_units")
    private String targetUnits;
    @Column(name = "insight_ref")
    private String insightRef;
    @Column(name = "budget_amount")
    private BigDecimal budgetAmount;
    @Column(name = "budget_currency")
    private String budgetCurrency;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "owners")
    private String owners;
    @Column(name = "kpis", columnDefinition = "text")
    private String kpis;
    @Column(name = "status")
    private String status;
    @Column(name = "published_at")
    private Instant publishedAt;
    @Column(name = "published_by")
    private String publishedBy;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version
    @Column(name = "version")
    private long version;

    public Campaign() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getCampaignRef() { return campaignRef; }
    public void setCampaignRef(String v) { this.campaignRef = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getRisk() { return risk; }
    public void setRisk(String v) { this.risk = v; }
    public String getObjective() { return objective; }
    public void setObjective(String v) { this.objective = v; }
    public String getTargetUnits() { return targetUnits; }
    public void setTargetUnits(String v) { this.targetUnits = v; }
    public String getInsightRef() { return insightRef; }
    public void setInsightRef(String v) { this.insightRef = v; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public void setBudgetAmount(BigDecimal v) { this.budgetAmount = v; }
    public String getBudgetCurrency() { return budgetCurrency; }
    public void setBudgetCurrency(String v) { this.budgetCurrency = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getOwners() { return owners; }
    public void setOwners(String v) { this.owners = v; }
    public String getKpis() { return kpis; }
    public void setKpis(String v) { this.kpis = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
    public String getPublishedBy() { return publishedBy; }
    public void setPublishedBy(String v) { this.publishedBy = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
}
