package com.wellnessz.e05.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** A fitness-for-duty decision against a versioned role standard (E05-F01). */
@Entity
@Table(name = "fitness_decision", schema = "fitness")
public class FitnessDecisionEntity {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "standard_code")
    private String standardCode;
    @Column(name = "standard_version")
    private int standardVersion;
    @Column(name = "outcome")
    private String outcome;
    @Column(name = "kernel_status")
    private String kernelStatus;
    @Column(name = "limitation_codes")
    private String limitationCodes;
    @Column(name = "restrictions", columnDefinition = "text")
    private String restrictions;
    @Column(name = "valid_from")
    private LocalDate validFrom;
    @Column(name = "valid_until")
    private LocalDate validUntil;
    @Column(name = "review_date")
    private LocalDate reviewDate;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "duration_override_reason", columnDefinition = "text")
    private String durationOverrideReason;
    @Column(name = "encounter_id")
    private UUID encounterId;
    @Column(name = "status")
    private String status;
    @Column(name = "required_signoff_role")
    private String requiredSignoffRole;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "signoff_by")
    private String signoffBy;
    @Column(name = "signoff_at")
    private Instant signoffAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "signoff_note", columnDefinition = "text")
    private String signoffNote;
    @Column(name = "certificate_document_id")
    private UUID certificateDocumentId;
    @Column(name = "crew_assessment")
    private boolean crewAssessment;
    @Column(name = "examination_date")
    private LocalDate examinationDate;
    @Column(name = "report_document_id")
    private UUID reportDocumentId;

    public FitnessDecisionEntity() {
        // JPA; populated through setters by the owning service
    }

    public static FitnessDecisionEntity create() {
        FitnessDecisionEntity x = new FitnessDecisionEntity();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getStandardCode() { return standardCode; }
    public void setStandardCode(String v) { this.standardCode = v; }
    public int getStandardVersion() { return standardVersion; }
    public void setStandardVersion(int v) { this.standardVersion = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getKernelStatus() { return kernelStatus; }
    public void setKernelStatus(String v) { this.kernelStatus = v; }
    public String getLimitationCodes() { return limitationCodes; }
    public void setLimitationCodes(String v) { this.limitationCodes = v; }
    public String getRestrictions() { return restrictions; }
    public void setRestrictions(String v) { this.restrictions = v; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate v) { this.validFrom = v; }
    public LocalDate getValidUntil() { return validUntil; }
    public void setValidUntil(LocalDate v) { this.validUntil = v; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate v) { this.reviewDate = v; }
    public String getRationale() { return rationale; }
    public void setRationale(String v) { this.rationale = v; }
    public String getDurationOverrideReason() { return durationOverrideReason; }
    public void setDurationOverrideReason(String v) { this.durationOverrideReason = v; }
    public UUID getEncounterId() { return encounterId; }
    public void setEncounterId(UUID v) { this.encounterId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRequiredSignoffRole() { return requiredSignoffRole; }
    public void setRequiredSignoffRole(String v) { this.requiredSignoffRole = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getSignoffBy() { return signoffBy; }
    public void setSignoffBy(String v) { this.signoffBy = v; }
    public Instant getSignoffAt() { return signoffAt; }
    public void setSignoffAt(Instant v) { this.signoffAt = v; }
    public String getSignoffNote() { return signoffNote; }
    public void setSignoffNote(String v) { this.signoffNote = v; }
    public UUID getCertificateDocumentId() { return certificateDocumentId; }
    public void setCertificateDocumentId(UUID v) { this.certificateDocumentId = v; }
    public boolean isCrewAssessment() { return crewAssessment; }
    public void setCrewAssessment(boolean v) { this.crewAssessment = v; }
    public LocalDate getExaminationDate() { return examinationDate; }
    public void setExaminationDate(LocalDate v) { this.examinationDate = v; }
    public UUID getReportDocumentId() { return reportDocumentId; }
    public void setReportDocumentId(UUID v) { this.reportDocumentId = v; }
}
