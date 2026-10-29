package com.wellnessz.e03.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Order for laboratory, imaging or clinic-device tests (FHIR ServiceRequest). */
@Entity
@Table(name = "lab_order", schema = "ohrecord")
public class LabOrder {

    @Id
    private UUID id;
    @Column(name = "placer_order_number")
    private String placerOrderNumber;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "category")
    private String category;
    @Column(name = "items", columnDefinition = "text")
    private String items;
    @Column(name = "order_set_code")
    private String orderSetCode;
    @Column(name = "priority")
    private String priority;
    @Column(name = "status")
    private String status;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "clinical_note", columnDefinition = "text")
    private String clinicalNote;
    @Column(name = "classification")
    private String classification;
    @Column(name = "ordered_by")
    private String orderedBy;
    @Column(name = "ordered_at")
    private Instant orderedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "hl7_message", columnDefinition = "text")
    private String hl7Message;
    @Column(name = "sent_at")
    private Instant sentAt;
    @Column(name = "cancelled_by")
    private String cancelledBy;
    @Column(name = "cancelled_at")
    private Instant cancelledAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public LabOrder() {
        // JPA; fields are populated through setters by the owning service
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getPlacerOrderNumber() {
        return placerOrderNumber;
    }

    public void setPlacerOrderNumber(String placerOrderNumber) {
        this.placerOrderNumber = placerOrderNumber;
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

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public String getOrderSetCode() {
        return orderSetCode;
    }

    public void setOrderSetCode(String orderSetCode) {
        this.orderSetCode = orderSetCode;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getClinicalNote() {
        return clinicalNote;
    }

    public void setClinicalNote(String clinicalNote) {
        this.clinicalNote = clinicalNote;
    }

    public String getClassification() {
        return classification;
    }

    public void setClassification(String classification) {
        this.classification = classification;
    }

    public String getOrderedBy() {
        return orderedBy;
    }

    public void setOrderedBy(String orderedBy) {
        this.orderedBy = orderedBy;
    }

    public Instant getOrderedAt() {
        return orderedAt;
    }

    public void setOrderedAt(Instant orderedAt) {
        this.orderedAt = orderedAt;
    }

    public String getHl7Message() {
        return hl7Message;
    }

    public void setHl7Message(String hl7Message) {
        this.hl7Message = hl7Message;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public String getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(String cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
