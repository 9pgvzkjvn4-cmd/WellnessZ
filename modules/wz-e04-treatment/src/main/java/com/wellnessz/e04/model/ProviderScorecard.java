package com.wellnessz.e04.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Monthly provider performance scorecard (E04-F06). */
@Entity(name = "TxProviderScorecard")
@Table(name = "provider_scorecard", schema = "treatment")
public class ProviderScorecard {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "provider_code")
    private String providerCode;
    @Column(name = "month")
    private String month;
    @Column(name = "referrals")
    private int referrals;
    @Column(name = "median_days_to_appointment")
    private BigDecimal medianDaysToAppointment;
    @Column(name = "report_within7_pct")
    private BigDecimal reportWithin7Pct;
    @Column(name = "avg_report_quality")
    private BigDecimal avgReportQuality;
    @Column(name = "outcome_improved_pct")
    private BigDecimal outcomeImprovedPct;
    @Column(name = "avg_invoice")
    private BigDecimal avgInvoice;
    @Column(name = "currency")
    private String currency;
    @Column(name = "timeliness")
    private BigDecimal timeliness;
    @Column(name = "report_quality")
    private BigDecimal reportQuality;
    @Column(name = "outcome_score")
    private BigDecimal outcomeScore;
    @Column(name = "cost_score")
    private BigDecimal costScore;
    @Column(name = "overall")
    private BigDecimal overall;
    @Column(name = "computed_at")
    private Instant computedAt;

    public ProviderScorecard() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public String getMonth() { return month; }
    public void setMonth(String v) { this.month = v; }
    public int getReferrals() { return referrals; }
    public void setReferrals(int v) { this.referrals = v; }
    public BigDecimal getMedianDaysToAppointment() { return medianDaysToAppointment; }
    public void setMedianDaysToAppointment(BigDecimal v) { this.medianDaysToAppointment = v; }
    public BigDecimal getReportWithin7Pct() { return reportWithin7Pct; }
    public void setReportWithin7Pct(BigDecimal v) { this.reportWithin7Pct = v; }
    public BigDecimal getAvgReportQuality() { return avgReportQuality; }
    public void setAvgReportQuality(BigDecimal v) { this.avgReportQuality = v; }
    public BigDecimal getOutcomeImprovedPct() { return outcomeImprovedPct; }
    public void setOutcomeImprovedPct(BigDecimal v) { this.outcomeImprovedPct = v; }
    public BigDecimal getAvgInvoice() { return avgInvoice; }
    public void setAvgInvoice(BigDecimal v) { this.avgInvoice = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public BigDecimal getTimeliness() { return timeliness; }
    public void setTimeliness(BigDecimal v) { this.timeliness = v; }
    public BigDecimal getReportQuality() { return reportQuality; }
    public void setReportQuality(BigDecimal v) { this.reportQuality = v; }
    public BigDecimal getOutcomeScore() { return outcomeScore; }
    public void setOutcomeScore(BigDecimal v) { this.outcomeScore = v; }
    public BigDecimal getCostScore() { return costScore; }
    public void setCostScore(BigDecimal v) { this.costScore = v; }
    public BigDecimal getOverall() { return overall; }
    public void setOverall(BigDecimal v) { this.overall = v; }
    public Instant getComputedAt() { return computedAt; }
    public void setComputedAt(Instant v) { this.computedAt = v; }
}
