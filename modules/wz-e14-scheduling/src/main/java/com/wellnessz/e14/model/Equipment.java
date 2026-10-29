package com.wellnessz.e14.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Equipment register entry (E14-F04): clinical devices and vaccine fridges. */
@Entity
@Table(name = "equipment", schema = "scheduling")
public class Equipment {

    @Id @Column(name = "id")
    private UUID id;
    @Column(name = "asset_tag")
    private String assetTag;
    @Column(name = "device_id")
    private String deviceId;
    @Column(name = "category")
    private String category;
    @Column(name = "model")
    private String model;
    @Column(name = "serial_number")
    private String serialNumber;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "status")
    private String status;
    @Column(name = "status_reason")
    private String statusReason;
    @Column(name = "calibration_interval_months")
    private Integer calibrationIntervalMonths;
    @Column(name = "last_calibrated_on")
    private LocalDate lastCalibratedOn;
    @Column(name = "calibration_due_on")
    private LocalDate calibrationDueOn;
    @Column(name = "last_certificate_number")
    private String lastCertificateNumber;
    @Column(name = "min_temp_c")
    private java.math.BigDecimal minTempC;
    @Column(name = "max_temp_c")
    private java.math.BigDecimal maxTempC;
    @Column(name = "due_alerted_for")
    private LocalDate dueAlertedFor;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;
    @Version @Column(name = "version")
    private long version;

    public Equipment() {
        // JPA; populated through setters by the owning service
    }

    public static Equipment create() {
        Equipment x = new Equipment();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getAssetTag() { return assetTag; }
    public void setAssetTag(String v) { this.assetTag = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { this.deviceId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String v) { this.serialNumber = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String v) { this.statusReason = v; }
    public Integer getCalibrationIntervalMonths() { return calibrationIntervalMonths; }
    public void setCalibrationIntervalMonths(Integer v) { this.calibrationIntervalMonths = v; }
    public LocalDate getLastCalibratedOn() { return lastCalibratedOn; }
    public void setLastCalibratedOn(LocalDate v) { this.lastCalibratedOn = v; }
    public LocalDate getCalibrationDueOn() { return calibrationDueOn; }
    public void setCalibrationDueOn(LocalDate v) { this.calibrationDueOn = v; }
    public String getLastCertificateNumber() { return lastCertificateNumber; }
    public void setLastCertificateNumber(String v) { this.lastCertificateNumber = v; }
    public java.math.BigDecimal getMinTempC() { return minTempC; }
    public void setMinTempC(java.math.BigDecimal v) { this.minTempC = v; }
    public java.math.BigDecimal getMaxTempC() { return maxTempC; }
    public void setMaxTempC(java.math.BigDecimal v) { this.maxTempC = v; }
    public LocalDate getDueAlertedFor() { return dueAlertedFor; }
    public void setDueAlertedFor(LocalDate v) { this.dueAlertedFor = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public long getVersion() { return version; }
    public void setVersion(long v) { this.version = v; }
}
