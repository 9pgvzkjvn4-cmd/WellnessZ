package com.wellnessz.e06.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** A version of the OH advice report answering a management advice request (functional wording only). */
@Entity
@Table(name = "advice_report", schema = "absence")
public class AdviceReport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "request_id")
    private UUID requestId;
    @Column(name = "version")
    private int version;
    @Column(name = "status")
    private String status;
    @Column(name = "fitness")
    private String fitness;
    @Column(name = "adjustments")
    private String adjustments;
    @Column(name = "restrictions")
    private String restrictions;
    @Column(name = "prognosis")
    private String prognosis;
    @Column(name = "expected_return")
    private LocalDate expectedReturn;
    @Column(name = "review_date")
    private LocalDate reviewDate;
    @Column(name = "answers")
    private String answers;
    @Column(name = "drafted_by")
    private String draftedBy;
    @Column(name = "drafted_at")
    private Instant draftedAt;
    @Column(name = "signed_by")
    private String signedBy;
    @Column(name = "signed_at")
    private Instant signedAt;
    @Column(name = "employee_decision")
    private String employeeDecision;
    @Column(name = "employee_comment")
    private String employeeComment;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decline_reason", columnDefinition = "text")
    private String declineReason;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "released_at")
    private Instant releasedAt;

    public AdviceReport() {
        // JPA; populated through setters by the owning service
    }

    public static AdviceReport create() {
        AdviceReport x = new AdviceReport();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getRequestId() { return requestId; }
    public void setRequestId(UUID v) { this.requestId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getFitness() { return fitness; }
    public void setFitness(String v) { this.fitness = v; }
    public String getAdjustments() { return adjustments; }
    public void setAdjustments(String v) { this.adjustments = v; }
    public String getRestrictions() { return restrictions; }
    public void setRestrictions(String v) { this.restrictions = v; }
    public String getPrognosis() { return prognosis; }
    public void setPrognosis(String v) { this.prognosis = v; }
    public LocalDate getExpectedReturn() { return expectedReturn; }
    public void setExpectedReturn(LocalDate v) { this.expectedReturn = v; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate v) { this.reviewDate = v; }
    public String getAnswers() { return answers; }
    public void setAnswers(String v) { this.answers = v; }
    public String getDraftedBy() { return draftedBy; }
    public void setDraftedBy(String v) { this.draftedBy = v; }
    public Instant getDraftedAt() { return draftedAt; }
    public void setDraftedAt(Instant v) { this.draftedAt = v; }
    public String getSignedBy() { return signedBy; }
    public void setSignedBy(String v) { this.signedBy = v; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant v) { this.signedAt = v; }
    public String getEmployeeDecision() { return employeeDecision; }
    public void setEmployeeDecision(String v) { this.employeeDecision = v; }
    public String getEmployeeComment() { return employeeComment; }
    public void setEmployeeComment(String v) { this.employeeComment = v; }
    public String getDeclineReason() { return declineReason; }
    public void setDeclineReason(String v) { this.declineReason = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant v) { this.releasedAt = v; }
}
