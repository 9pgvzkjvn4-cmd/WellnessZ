package com.wellnessz.e11.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Travel / posting consultation (E11-F01). Prophylaxis and advice are encrypted. */
@Entity(name = "E11Consultation")
@Table(name = "consultation", schema = "travelhealth")
public class Consultation {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "destinations")
    private String destinations;
    @Column(name = "depart_date")
    private LocalDate departDate;
    @Column(name = "return_date")
    private LocalDate returnDate;
    @Column(name = "purpose")
    private String purpose;
    @Column(name = "vaccines_given", columnDefinition = "text")
    private String vaccinesGiven;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "prophylaxis", columnDefinition = "text")
    private String prophylaxis;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "advice", columnDefinition = "text")
    private String advice;
    @Column(name = "recommendations", columnDefinition = "text")
    private String recommendations;
    @Column(name = "certificate_document_id")
    private UUID certificateDocumentId;
    @Column(name = "consulted_by")
    private String consultedBy;
    @Column(name = "created_at")
    private Instant createdAt;

    /** New instance with a random id. */
    public static Consultation create() {
        Consultation e = new Consultation();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String v) {
        this.employeeId = v;
    }

    public String getDestinations() {
        return destinations;
    }

    public void setDestinations(String v) {
        this.destinations = v;
    }

    public LocalDate getDepartDate() {
        return departDate;
    }

    public void setDepartDate(LocalDate v) {
        this.departDate = v;
    }

    public LocalDate getReturnDate() {
        return returnDate;
    }

    public void setReturnDate(LocalDate v) {
        this.returnDate = v;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String v) {
        this.purpose = v;
    }

    public String getVaccinesGiven() {
        return vaccinesGiven;
    }

    public void setVaccinesGiven(String v) {
        this.vaccinesGiven = v;
    }

    public String getProphylaxis() {
        return prophylaxis;
    }

    public void setProphylaxis(String v) {
        this.prophylaxis = v;
    }

    public String getAdvice() {
        return advice;
    }

    public void setAdvice(String v) {
        this.advice = v;
    }

    public String getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(String v) {
        this.recommendations = v;
    }

    public UUID getCertificateDocumentId() {
        return certificateDocumentId;
    }

    public void setCertificateDocumentId(UUID v) {
        this.certificateDocumentId = v;
    }

    public String getConsultedBy() {
        return consultedBy;
    }

    public void setConsultedBy(String v) {
        this.consultedBy = v;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant v) {
        this.createdAt = v;
    }
}
