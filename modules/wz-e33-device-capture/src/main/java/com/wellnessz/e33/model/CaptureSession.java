package com.wellnessz.e33.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.wellnessz.secrets.EncryptedStringConverter;

/**
 * Capture session (contract 14.1): binds one employee (and optional case / order) in one OH form to one device instance for
 * one measurement – or, in KIOSK mode, to the instances of a self-measurement kiosk after badge identification and consent.
 * State changes go through {@link com.wellnessz.e33.domain.CaptureStateMachine}.
 */
@Entity(name = "E33CaptureSession")
@Table(name = "capture_session", schema = "devicecap")
public class CaptureSession {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "tenant_code")
    private String tenantCode = "GROUP";
    @Column(name = "state")
    private String state;
    @Column(name = "mode")
    private String mode;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "order_ref")
    private String orderRef;
    @Column(name = "form_code")
    private String formCode;
    @Column(name = "form_version")
    private String formVersion;
    @Column(name = "field_group")
    private String fieldGroup;
    @Column(name = "category")
    private String category;
    @Column(name = "restricted")
    private boolean restricted;
    @Column(name = "device_instance_id")
    private String deviceInstanceId;
    @Column(name = "original_device_instance_id")
    private String originalDeviceInstanceId;
    @Column(name = "kiosk_instances")
    private String kioskInstances;
    @Column(name = "endpoint_id")
    private String endpointId;
    @Column(name = "path")
    private String path;
    @Column(name = "operator")
    private String operator;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "station_code")
    private String stationCode;
    @Column(name = "batch_id")
    private UUID batchId;
    @Column(name = "queue_position")
    private Integer queuePosition;
    @Column(name = "context_push")
    private String contextPush;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "last_activity_at")
    private Instant lastActivityAt;
    @Column(name = "closed_at")
    private Instant closedAt;
    @Column(name = "observation_id")
    private UUID observationId;
    @Column(name = "observation_status")
    private String observationStatus;
    @Column(name = "flags", columnDefinition = "text")
    private String flags;
    @Column(name = "routed_record_type")
    private String routedRecordType;
    @Column(name = "routed_record_id")
    private String routedRecordId;
    @Column(name = "fallback_reason")
    private String fallbackReason;
    @Column(name = "reject_reason")
    private String rejectReason;
    @Column(name = "retest_count")
    private int retestCount;
    @Column(name = "verified_by")
    private String verifiedBy;
    @Column(name = "verified_at")
    private Instant verifiedAt;
    @Column(name = "interpretation_status")
    private String interpretationStatus;
    @Column(name = "signed_by")
    private String signedBy;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "consent_id")
    private UUID consentId;
    @Column(name = "language")
    private String language;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Version
    @Column(name = "version")
    private Long version;

    public CaptureSession() {
        // JPA; populated by the session service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getTenantCode() { return tenantCode; }
    public void setTenantCode(String v) { this.tenantCode = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getMode() { return mode; }
    public void setMode(String v) { this.mode = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getOrderRef() { return orderRef; }
    public void setOrderRef(String v) { this.orderRef = v; }
    public String getFormCode() { return formCode; }
    public void setFormCode(String v) { this.formCode = v; }
    public String getFormVersion() { return formVersion; }
    public void setFormVersion(String v) { this.formVersion = v; }
    public String getFieldGroup() { return fieldGroup; }
    public void setFieldGroup(String v) { this.fieldGroup = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean v) { this.restricted = v; }
    public String getDeviceInstanceId() { return deviceInstanceId; }
    public void setDeviceInstanceId(String v) { this.deviceInstanceId = v; }
    public String getOriginalDeviceInstanceId() { return originalDeviceInstanceId; }
    public void setOriginalDeviceInstanceId(String v) { this.originalDeviceInstanceId = v; }
    public String getKioskInstances() { return kioskInstances; }
    public void setKioskInstances(String v) { this.kioskInstances = v; }
    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String v) { this.endpointId = v; }
    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }
    public String getOperator() { return operator; }
    public void setOperator(String v) { this.operator = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getStationCode() { return stationCode; }
    public void setStationCode(String v) { this.stationCode = v; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID v) { this.batchId = v; }
    public Integer getQueuePosition() { return queuePosition; }
    public void setQueuePosition(Integer v) { this.queuePosition = v; }
    public String getContextPush() { return contextPush; }
    public void setContextPush(String v) { this.contextPush = v; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant v) { this.openedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getLastActivityAt() { return lastActivityAt; }
    public void setLastActivityAt(Instant v) { this.lastActivityAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public UUID getObservationId() { return observationId; }
    public void setObservationId(UUID v) { this.observationId = v; }
    public String getObservationStatus() { return observationStatus; }
    public void setObservationStatus(String v) { this.observationStatus = v; }
    public String getFlags() { return flags; }
    public void setFlags(String v) { this.flags = v; }
    public String getRoutedRecordType() { return routedRecordType; }
    public void setRoutedRecordType(String v) { this.routedRecordType = v; }
    public String getRoutedRecordId() { return routedRecordId; }
    public void setRoutedRecordId(String v) { this.routedRecordId = v; }
    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String v) { this.fallbackReason = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { this.rejectReason = v; }
    public int getRetestCount() { return retestCount; }
    public void setRetestCount(int v) { this.retestCount = v; }
    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String v) { this.verifiedBy = v; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant v) { this.verifiedAt = v; }
    public String getInterpretationStatus() { return interpretationStatus; }
    public void setInterpretationStatus(String v) { this.interpretationStatus = v; }
    public String getSignedBy() { return signedBy; }
    public void setSignedBy(String v) { this.signedBy = v; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant v) { this.signedAt = v; }
    public UUID getConsentId() { return consentId; }
    public void setConsentId(UUID v) { this.consentId = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Long getVersion() { return version; }
}
