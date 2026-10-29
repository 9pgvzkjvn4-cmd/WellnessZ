package com.wellnessz.e03.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Test result from the LIS, a clinic device or manual entry (FHIR Observation). */
@Entity
@Table(name = "result", schema = "ohrecord")
public class Result {

    @Id
    private UUID id;
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "category")
    private String category;
    @Column(name = "code_system")
    private String codeSystem;
    @Column(name = "code")
    private String code;
    @Column(name = "code_display")
    private String codeDisplay;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value", columnDefinition = "text")
    private String value;
    @Column(name = "unit")
    private String unit;
    @Column(name = "reference_range")
    private String referenceRange;
    @Column(name = "flag")
    private String flag;
    @Column(name = "status")
    private String status;
    @Column(name = "effective_at")
    private Instant effectiveAt;
    @Column(name = "received_at")
    private Instant receivedAt;
    @Column(name = "source")
    private String source;
    @Column(name = "device_id")
    private String deviceId;
    @Column(name = "device_model")
    private String deviceModel;
    @Column(name = "calibration_id")
    private String calibrationId;
    @Column(name = "calibration_date")
    private LocalDate calibrationDate;
    @Column(name = "measurements", columnDefinition = "text")
    private String measurements;
    @Column(name = "detail", columnDefinition = "text")
    private String detail;
    @Column(name = "baseline")
    private boolean baseline;
    @Column(name = "classification")
    private String classification;
    @Column(name = "acknowledged_by")
    private String acknowledgedBy;
    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;
    @Column(name = "ack_action")
    private String ackAction;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "ack_note", columnDefinition = "text")
    private String ackNote;
    @Column(name = "escalated_at")
    private Instant escalatedAt;
    @Column(name = "letter_document_id")
    private UUID letterDocumentId;
    @Column(name = "recorded_by")
    private String recordedBy;

    public Result() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCodeSystem() {
        return codeSystem;
    }

    public void setCodeSystem(String codeSystem) {
        this.codeSystem = codeSystem;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getCodeDisplay() {
        return codeDisplay;
    }

    public void setCodeDisplay(String codeDisplay) {
        this.codeDisplay = codeDisplay;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getReferenceRange() {
        return referenceRange;
    }

    public void setReferenceRange(String referenceRange) {
        this.referenceRange = referenceRange;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getEffectiveAt() {
        return effectiveAt;
    }

    public void setEffectiveAt(Instant effectiveAt) {
        this.effectiveAt = effectiveAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public void setDeviceModel(String deviceModel) {
        this.deviceModel = deviceModel;
    }

    public String getCalibrationId() {
        return calibrationId;
    }

    public void setCalibrationId(String calibrationId) {
        this.calibrationId = calibrationId;
    }

    public LocalDate getCalibrationDate() {
        return calibrationDate;
    }

    public void setCalibrationDate(LocalDate calibrationDate) {
        this.calibrationDate = calibrationDate;
    }

    public String getMeasurements() {
        return measurements;
    }

    public void setMeasurements(String measurements) {
        this.measurements = measurements;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public boolean isBaseline() {
        return baseline;
    }

    public void setBaseline(boolean baseline) {
        this.baseline = baseline;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public void setAcknowledgedBy(String acknowledgedBy) {
        this.acknowledgedBy = acknowledgedBy;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public void setAcknowledgedAt(Instant acknowledgedAt) {
        this.acknowledgedAt = acknowledgedAt;
    }

    public String getAckAction() {
        return ackAction;
    }

    public void setAckAction(String ackAction) {
        this.ackAction = ackAction;
    }

    public String getAckNote() {
        return ackNote;
    }

    public void setAckNote(String ackNote) {
        this.ackNote = ackNote;
    }

    public Instant getEscalatedAt() {
        return escalatedAt;
    }

    public void setEscalatedAt(Instant escalatedAt) {
        this.escalatedAt = escalatedAt;
    }

    public UUID getLetterDocumentId() {
        return letterDocumentId;
    }

    public void setLetterDocumentId(UUID letterDocumentId) {
        this.letterDocumentId = letterDocumentId;
    }

    public String getRecordedBy() {
        return recordedBy;
    }

    public void setRecordedBy(String recordedBy) {
        this.recordedBy = recordedBy;
    }
}
