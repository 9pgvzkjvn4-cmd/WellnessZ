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

/** Food-handler exclusion / return episode (E05-F05). Reason and checklist are OH-only. */
@Entity
@Table(name = "food_handler_episode", schema = "fitness")
public class FoodHandlerEpisode {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "department_code")
    private String departmentCode;
    @Column(name = "status")
    private String status;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "reason_category", columnDefinition = "text")
    private String reasonCategory;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "checklist", columnDefinition = "text")
    private String checklist;
    @Column(name = "excluded_from")
    private LocalDate excludedFrom;
    @Column(name = "expected_return")
    private LocalDate expectedReturn;
    @Column(name = "return_requested_at")
    private Instant returnRequestedAt;
    @Column(name = "return_requested_by")
    private String returnRequestedBy;
    @Column(name = "cleared_at")
    private Instant clearedAt;
    @Column(name = "cleared_by")
    private String clearedBy;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "criteria", columnDefinition = "text")
    private String criteria;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "clearance_note", columnDefinition = "text")
    private String clearanceNote;
    @Column(name = "document_id")
    private UUID documentId;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public FoodHandlerEpisode() {
        // JPA; populated through setters by the owning service
    }

    public static FoodHandlerEpisode create() {
        FoodHandlerEpisode x = new FoodHandlerEpisode();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getDepartmentCode() { return departmentCode; }
    public void setDepartmentCode(String v) { this.departmentCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReasonCategory() { return reasonCategory; }
    public void setReasonCategory(String v) { this.reasonCategory = v; }
    public String getChecklist() { return checklist; }
    public void setChecklist(String v) { this.checklist = v; }
    public LocalDate getExcludedFrom() { return excludedFrom; }
    public void setExcludedFrom(LocalDate v) { this.excludedFrom = v; }
    public LocalDate getExpectedReturn() { return expectedReturn; }
    public void setExpectedReturn(LocalDate v) { this.expectedReturn = v; }
    public Instant getReturnRequestedAt() { return returnRequestedAt; }
    public void setReturnRequestedAt(Instant v) { this.returnRequestedAt = v; }
    public String getReturnRequestedBy() { return returnRequestedBy; }
    public void setReturnRequestedBy(String v) { this.returnRequestedBy = v; }
    public Instant getClearedAt() { return clearedAt; }
    public void setClearedAt(Instant v) { this.clearedAt = v; }
    public String getClearedBy() { return clearedBy; }
    public void setClearedBy(String v) { this.clearedBy = v; }
    public String getCriteria() { return criteria; }
    public void setCriteria(String v) { this.criteria = v; }
    public String getClearanceNote() { return clearanceNote; }
    public void setClearanceNote(String v) { this.clearanceNote = v; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID v) { this.documentId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
