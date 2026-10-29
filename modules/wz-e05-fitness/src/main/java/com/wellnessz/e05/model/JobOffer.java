package com.wellnessz.e05.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Electronic alternative-duty job offer issued by HR (E05-BL-008). */
@Entity
@Table(name = "job_offer", schema = "fitness")
public class JobOffer {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "case_ref")
    private String caseRef;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "job_title")
    private String jobTitle;
    @Column(name = "duties")
    private String duties;
    @Column(name = "location")
    private String location;
    @Column(name = "shift")
    private String shift;
    @Column(name = "hours_per_week")
    private BigDecimal hoursPerWeek;
    @Column(name = "pay_basis")
    private String payBasis;
    @Column(name = "pay_note")
    private String payNote;
    @Column(name = "restrictions", columnDefinition = "text")
    private String restrictions;
    @Column(name = "start_date")
    private LocalDate startDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(name = "contact_name")
    private String contactName;
    @Column(name = "contact_email")
    private String contactEmail;
    @Column(name = "respond_by")
    private LocalDate respondBy;
    @Column(name = "status")
    private String status;
    @Column(name = "issued_by")
    private String issuedBy;
    @Column(name = "issued_at")
    private Instant issuedAt;
    @Column(name = "responded_at")
    private Instant respondedAt;
    @Column(name = "response_comment")
    private String responseComment;
    @Column(name = "document_id")
    private UUID documentId;

    public JobOffer() {
        // JPA; populated through setters by the owning service
    }

    public static JobOffer create() {
        JobOffer x = new JobOffer();
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
    public String getJobCode() { return jobCode; }
    public void setJobCode(String v) { this.jobCode = v; }
    public String getJobTitle() { return jobTitle; }
    public void setJobTitle(String v) { this.jobTitle = v; }
    public String getDuties() { return duties; }
    public void setDuties(String v) { this.duties = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getShift() { return shift; }
    public void setShift(String v) { this.shift = v; }
    public BigDecimal getHoursPerWeek() { return hoursPerWeek; }
    public void setHoursPerWeek(BigDecimal v) { this.hoursPerWeek = v; }
    public String getPayBasis() { return payBasis; }
    public void setPayBasis(String v) { this.payBasis = v; }
    public String getPayNote() { return payNote; }
    public void setPayNote(String v) { this.payNote = v; }
    public String getRestrictions() { return restrictions; }
    public void setRestrictions(String v) { this.restrictions = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate v) { this.startDate = v; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate v) { this.endDate = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
    public String getContactEmail() { return contactEmail; }
    public void setContactEmail(String v) { this.contactEmail = v; }
    public LocalDate getRespondBy() { return respondBy; }
    public void setRespondBy(LocalDate v) { this.respondBy = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getIssuedBy() { return issuedBy; }
    public void setIssuedBy(String v) { this.issuedBy = v; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant v) { this.issuedAt = v; }
    public Instant getRespondedAt() { return respondedAt; }
    public void setRespondedAt(Instant v) { this.respondedAt = v; }
    public String getResponseComment() { return responseComment; }
    public void setResponseComment(String v) { this.responseComment = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
}
