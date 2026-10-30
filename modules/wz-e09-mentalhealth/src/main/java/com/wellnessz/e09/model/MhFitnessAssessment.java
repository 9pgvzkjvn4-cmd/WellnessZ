package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Safety-critical mental-health fitness assessment (E09-F06); medications and findings encrypted, insert-only. */
@Entity(name = "E09MhFitnessAssessment")
@Table(name = "fitness_assessment", schema = "mentalhealth")
public class MhFitnessAssessment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "kernel_case_id")
    private UUID kernelCaseId;
    @Column(name = "standard_code")
    private String standardCode;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "medications", columnDefinition = "text")
    private String medications;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "findings", columnDefinition = "text")
    private String findings;
    @Column(name = "decision")
    private String decision;
    @Column(name = "limitations")
    private String limitations;
    @Column(name = "review_date")
    private LocalDate reviewDate;
    @Column(name = "authority_report_required")
    private boolean authorityReportRequired;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "review_task_id")
    private UUID reviewTaskId;
    @Column(name = "assessed_by")
    private String assessedBy;
    @Column(name = "assessed_at")
    private Instant assessedAt;

    public MhFitnessAssessment() {
        // JPA; populated through setters by the owning service
    }

    public static MhFitnessAssessment create() {
        MhFitnessAssessment x = new MhFitnessAssessment();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public UUID getKernelCaseId() { return kernelCaseId; }
    public void setKernelCaseId(UUID v) { this.kernelCaseId = v; }
    public String getStandardCode() { return standardCode; }
    public void setStandardCode(String v) { this.standardCode = v; }
    public String getMedications() { return medications; }
    public void setMedications(String v) { this.medications = v; }
    public String getFindings() { return findings; }
    public void setFindings(String v) { this.findings = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public String getLimitations() { return limitations; }
    public void setLimitations(String v) { this.limitations = v; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate v) { this.reviewDate = v; }
    public boolean isAuthorityReportRequired() { return authorityReportRequired; }
    public void setAuthorityReportRequired(boolean v) { this.authorityReportRequired = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public UUID getReviewTaskId() { return reviewTaskId; }
    public void setReviewTaskId(UUID v) { this.reviewTaskId = v; }
    public String getAssessedBy() { return assessedBy; }
    public void setAssessedBy(String v) { this.assessedBy = v; }
    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant v) { this.assessedAt = v; }
}
