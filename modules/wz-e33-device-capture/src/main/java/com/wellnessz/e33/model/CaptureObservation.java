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

/** An engine observation that reached a capture session, with the verifier's decision and the accepted values (encrypted). */
@Entity(name = "E33CaptureObservation")
@Table(name = "capture_observation", schema = "devicecap")
public class CaptureObservation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "session_id")
    private UUID sessionId;
    @Column(name = "observation_id")
    private UUID observationId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "category")
    private String category;
    @Column(name = "capture_method")
    private String captureMethod;
    @Column(name = "device_instance_id")
    private String deviceInstanceId;
    @Column(name = "model")
    private String model;
    @Column(name = "status")
    private String status;
    @Column(name = "flags", columnDefinition = "text")
    private String flags;
    @Column(name = "effective_at")
    private Instant effectiveAt;
    @Column(name = "received_at")
    private Instant receivedAt;
    @Column(name = "decision")
    private String decision;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "reject_reason")
    private String rejectReason;
    @Column(name = "routed_record_type")
    private String routedRecordType;
    @Column(name = "routed_record_id")
    private String routedRecordId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "values_json", columnDefinition = "text")
    private String valuesJson;
    @Version
    @Column(name = "version")
    private Long version;

    public CaptureObservation() {
        // JPA
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSessionId() { return sessionId; }
    public void setSessionId(UUID v) { this.sessionId = v; }
    public UUID getObservationId() { return observationId; }
    public void setObservationId(UUID v) { this.observationId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getCaptureMethod() { return captureMethod; }
    public void setCaptureMethod(String v) { this.captureMethod = v; }
    public String getDeviceInstanceId() { return deviceInstanceId; }
    public void setDeviceInstanceId(String v) { this.deviceInstanceId = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getFlags() { return flags; }
    public void setFlags(String v) { this.flags = v; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant v) { this.effectiveAt = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String v) { this.rejectReason = v; }
    public String getRoutedRecordType() { return routedRecordType; }
    public void setRoutedRecordType(String v) { this.routedRecordType = v; }
    public String getRoutedRecordId() { return routedRecordId; }
    public void setRoutedRecordId(String v) { this.routedRecordId = v; }
    public String getValuesJson() { return valuesJson; }
    public void setValuesJson(String v) { this.valuesJson = v; }
}
