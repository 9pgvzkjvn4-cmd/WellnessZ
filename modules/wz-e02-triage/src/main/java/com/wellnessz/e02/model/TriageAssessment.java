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

/** Triage assessment with protocol version, answers (encrypted), computed vs final urgency and override reason. */
@Entity
@Table(name = "triage_assessment", schema = "triage")
public class TriageAssessment {

    @Id
    private UUID id;
    @Column(name = "case_id", nullable = false)
    private UUID caseId;
    @Column(name = "protocol_code", nullable = false, length = 40)
    private String protocolCode;
    @Column(name = "protocol_version", nullable = false)
    private int protocolVersion;
    @Column(nullable = false, length = 20)
    private String mode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String answers;
    @Column(name = "red_flags", length = 500)
    private String redFlags;
    @Column(name = "computed_urgency", nullable = false, length = 20)
    private String computedUrgency;
    @Column(name = "final_urgency", nullable = false, length = 20)
    private String finalUrgency;
    @Column(length = 500)
    private String disposition;
    @Column(name = "advice_key", length = 60)
    private String adviceKey;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "override_reason", columnDefinition = "text")
    private String overrideReason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "call_log", columnDefinition = "text")
    private String callLog;
    @Column(name = "assessed_by", nullable = false, length = 200)
    private String assessedBy;
    @Column(name = "assessed_at", nullable = false)
    private Instant assessedAt;

    public TriageAssessment() {
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

    public String getProtocolCode() {
        return protocolCode;
    }

    public void setProtocolCode(String protocolCode) {
        this.protocolCode = protocolCode;
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(int protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getAnswers() {
        return answers;
    }

    public void setAnswers(String answers) {
        this.answers = answers;
    }

    public String getRedFlags() {
        return redFlags;
    }

    public void setRedFlags(String redFlags) {
        this.redFlags = redFlags;
    }

    public String getComputedUrgency() {
        return computedUrgency;
    }

    public void setComputedUrgency(String computedUrgency) {
        this.computedUrgency = computedUrgency;
    }

    public String getFinalUrgency() {
        return finalUrgency;
    }

    public void setFinalUrgency(String finalUrgency) {
        this.finalUrgency = finalUrgency;
    }

    public String getDisposition() {
        return disposition;
    }

    public void setDisposition(String disposition) {
        this.disposition = disposition;
    }

    public String getAdviceKey() {
        return adviceKey;
    }

    public void setAdviceKey(String adviceKey) {
        this.adviceKey = adviceKey;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(String overrideReason) {
        this.overrideReason = overrideReason;
    }

    public String getCallLog() {
        return callLog;
    }

    public void setCallLog(String callLog) {
        this.callLog = callLog;
    }

    public String getAssessedBy() {
        return assessedBy;
    }

    public void setAssessedBy(String assessedBy) {
        this.assessedBy = assessedBy;
    }

    public Instant getAssessedAt() {
        return assessedAt;
    }

    public void setAssessedAt(Instant assessedAt) {
        this.assessedAt = assessedAt;
    }
}
