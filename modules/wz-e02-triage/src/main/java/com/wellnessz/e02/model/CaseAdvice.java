package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Advice released to the employee portal (E02-BL-003). */
@Entity
@Table(name = "case_advice", schema = "triage")
public class CaseAdvice {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "advice_key", nullable = false, length = 60)
    private String adviceKey;
    @Column(length = 8)
    private String language;
    @Column(name = "sent_by", nullable = false, length = 200)
    private String sentBy;
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    public CaseAdvice() {
        // JPA; fields are populated through setters by the owning service
    }


    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public void setCaseId(UUID caseId) {
        this.caseId = caseId;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public void setEmployeeId(String employeeId) {
        this.employeeId = employeeId;
    }

    public String getAdviceKey() {
        return adviceKey;
    }

    public void setAdviceKey(String adviceKey) {
        this.adviceKey = adviceKey;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getSentBy() {
        return sentBy;
    }

    public void setSentBy(String sentBy) {
        this.sentBy = sentBy;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }
}
