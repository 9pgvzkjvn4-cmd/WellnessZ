package com.wellnessz.e06.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Management advice request from a line manager or HR (E06-F05). The context is visible to OH and the requester only. */
@Entity
@Table(name = "advice_request", schema = "absence")
public class AdviceRequest {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "requested_by")
    private String requestedBy;
    @Column(name = "requester_role")
    private String requesterRole;
    @Column(name = "requester_employee_id")
    private String requesterEmployeeId;
    @Column(name = "question_codes")
    private String questionCodes;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "context", columnDefinition = "text")
    private String context;
    @Column(name = "episode_id")
    private UUID episodeId;
    @Column(name = "status")
    private String status;
    @Column(name = "requested_at")
    private Instant requestedAt;
    @Column(name = "sla_due_at")
    private Instant slaDueAt;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "accepted_by")
    private String acceptedBy;
    @Column(name = "accepted_at")
    private Instant acceptedAt;
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public AdviceRequest() {
        // JPA; populated through setters by the owning service
    }

    public static AdviceRequest create() {
        AdviceRequest x = new AdviceRequest();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public String getRequesterRole() { return requesterRole; }
    public void setRequesterRole(String v) { this.requesterRole = v; }
    public String getRequesterEmployeeId() { return requesterEmployeeId; }
    public void setRequesterEmployeeId(String v) { this.requesterEmployeeId = v; }
    public String getQuestionCodes() { return questionCodes; }
    public void setQuestionCodes(String v) { this.questionCodes = v; }
    public String getContext() { return context; }
    public void setContext(String v) { this.context = v; }
    public UUID getEpisodeId() { return episodeId; }
    public void setEpisodeId(UUID v) { this.episodeId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public void setSlaDueAt(Instant v) { this.slaDueAt = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getCaseRef() { return caseRef; }
    public void setCaseRef(String v) { this.caseRef = v; }
    public String getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(String v) { this.acceptedBy = v; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant v) { this.acceptedAt = v; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
