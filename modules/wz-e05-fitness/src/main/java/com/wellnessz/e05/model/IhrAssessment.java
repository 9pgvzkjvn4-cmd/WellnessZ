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

/** Ill-health retirement / redeployment assessment with CMO decision and appeal (E05-F07). */
@Entity
@Table(name = "ihr_assessment", schema = "fitness")
public class IhrAssessment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "assessment_type")
    private String assessmentType;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "findings", columnDefinition = "text")
    private String findings;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recommendation", columnDefinition = "text")
    private String recommendation;
    @Column(name = "policy_reference")
    private String policyReference;
    @Column(name = "status")
    private String status;
    @Column(name = "assessed_by")
    private String assessedBy;
    @Column(name = "assessed_at")
    private Instant assessedAt;
    @Column(name = "approved")
    private Boolean approved;
    @Column(name = "decision_text")
    private String decisionText;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "cmo_note", columnDefinition = "text")
    private String cmoNote;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "appeal_deadline")
    private LocalDate appealDeadline;
    @Column(name = "letter_document_id")
    private UUID letterDocumentId;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "appeal_grounds", columnDefinition = "text")
    private String appealGrounds;
    @Column(name = "second_opinion_by")
    private String secondOpinionBy;
    @Column(name = "appealed_by")
    private String appealedBy;
    @Column(name = "appealed_at")
    private Instant appealedAt;
    @Column(name = "appeal_due_by")
    private LocalDate appealDueBy;
    @Column(name = "appeal_upheld")
    private Boolean appealUpheld;
    @Column(name = "appeal_decision_text")
    private String appealDecisionText;
    @Column(name = "appeal_decided_by")
    private String appealDecidedBy;
    @Column(name = "appeal_decided_at")
    private Instant appealDecidedAt;
    @Column(name = "appeal_letter_document_id")
    private UUID appealLetterDocumentId;

    public IhrAssessment() {
        // JPA; populated through setters by the owning service
    }

    public static IhrAssessment create() {
        IhrAssessment x = new IhrAssessment();
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
    public String getAssessmentType() { return assessmentType; }
    public void setAssessmentType(String v) { this.assessmentType = v; }
    public String getFindings() { return findings; }
    public void setFindings(String v) { this.findings = v; }
    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String v) { this.recommendation = v; }
    public String getPolicyReference() { return policyReference; }
    public void setPolicyReference(String v) { this.policyReference = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getAssessedBy() { return assessedBy; }
    public void setAssessedBy(String v) { this.assessedBy = v; }
    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant v) { this.assessedAt = v; }
    public Boolean getApproved() { return approved; }
    public void setApproved(Boolean v) { this.approved = v; }
    public String getDecisionText() { return decisionText; }
    public void setDecisionText(String v) { this.decisionText = v; }
    public String getCmoNote() { return cmoNote; }
    public void setCmoNote(String v) { this.cmoNote = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public LocalDate getAppealDeadline() { return appealDeadline; }
    public void setAppealDeadline(LocalDate v) { this.appealDeadline = v; }
    public UUID getLetterDocumentId() { return letterDocumentId; }
    public void setLetterDocumentId(UUID v) { this.letterDocumentId = v; }
    public String getAppealGrounds() { return appealGrounds; }
    public void setAppealGrounds(String v) { this.appealGrounds = v; }
    public String getSecondOpinionBy() { return secondOpinionBy; }
    public void setSecondOpinionBy(String v) { this.secondOpinionBy = v; }
    public String getAppealedBy() { return appealedBy; }
    public void setAppealedBy(String v) { this.appealedBy = v; }
    public Instant getAppealedAt() { return appealedAt; }
    public void setAppealedAt(Instant v) { this.appealedAt = v; }
    public LocalDate getAppealDueBy() { return appealDueBy; }
    public void setAppealDueBy(LocalDate v) { this.appealDueBy = v; }
    public Boolean getAppealUpheld() { return appealUpheld; }
    public void setAppealUpheld(Boolean v) { this.appealUpheld = v; }
    public String getAppealDecisionText() { return appealDecisionText; }
    public void setAppealDecisionText(String v) { this.appealDecisionText = v; }
    public String getAppealDecidedBy() { return appealDecidedBy; }
    public void setAppealDecidedBy(String v) { this.appealDecidedBy = v; }
    public Instant getAppealDecidedAt() { return appealDecidedAt; }
    public void setAppealDecidedAt(Instant v) { this.appealDecidedAt = v; }
    public UUID getAppealLetterDocumentId() { return appealLetterDocumentId; }
    public void setAppealLetterDocumentId(UUID v) { this.appealLetterDocumentId = v; }
}
