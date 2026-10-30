package com.wellnessz.e12.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Daily wearable aggregate (data minimisation: one value per metric and day). */
@Entity(name = "E12WearableDaily")
@Table(name = "wearable_daily", schema = "promotion")
public class WearableDaily {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "enrolment_id")
    private UUID enrolmentId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "campaign_id")
    private UUID campaignId;
    @Column(name = "metric")
    private String metric;
    @Column(name = "metric_date")
    private LocalDate metricDate;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value", columnDefinition = "text")
    private String value;
    @Column(name = "received_at")
    private Instant receivedAt;

    public WearableDaily() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getEnrolmentId() { return enrolmentId; }
    public void setEnrolmentId(UUID v) { this.enrolmentId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCampaignId() { return campaignId; }
    public void setCampaignId(UUID v) { this.campaignId = v; }
    public String getMetric() { return metric; }
    public void setMetric(String v) { this.metric = v; }
    public LocalDate getMetricDate() { return metricDate; }
    public void setMetricDate(LocalDate v) { this.metricDate = v; }
    public String getValue() { return value; }
    public void setValue(String v) { this.value = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
}
