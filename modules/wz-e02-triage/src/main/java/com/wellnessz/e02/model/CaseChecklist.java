package com.wellnessz.e02.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.wellnessz.secrets.EncryptedStringConverter;

/** Safety-critical role checklist (food handler / driving / height) – mandatory before closure (E02-F02). */
@Entity
@Table(name = "case_checklist", schema = "triage")
public class CaseChecklist {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(nullable = false, length = 20)
    private String code;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String answers;
    @Column(nullable = false, length = 20)
    private String outcome;
    @Column(name = "completed_by", nullable = false, length = 200)
    private String completedBy;
    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public CaseChecklist() {
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getAnswers() {
        return answers;
    }

    public void setAnswers(String answers) {
        this.answers = answers;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(String completedBy) {
        this.completedBy = completedBy;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
}
