package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Follow-up of a treatment plan (OH review, phone, physio, specialist, telehealth). */
@Entity(name = "TxPlanFollowUp")
@Table(name = "follow_up", schema = "treatment")
public class PlanFollowUp {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "plan_id")
    private UUID planId;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "follow_up_type")
    private String type;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "status")
    private String status;
    @Column(name = "appointment_id")
    private UUID appointmentId;
    @Column(name = "task_id")
    private UUID taskId;
    @Column(name = "employee_response")
    private String employeeResponse;
    @Column(name = "response_reason")
    private String responseReason;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "reminded_at")
    private Instant remindedAt;
    @Column(name = "book_requested")
    private boolean bookRequested;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public PlanFollowUp() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID v) { this.planId = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
    public UUID getTaskId() { return taskId; }
    public void setTaskId(UUID v) { this.taskId = v; }
    public String getEmployeeResponse() { return employeeResponse; }
    public void setEmployeeResponse(String v) { this.employeeResponse = v; }
    public String getResponseReason() { return responseReason; }
    public void setResponseReason(String v) { this.responseReason = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getRemindedAt() { return remindedAt; }
    public void setRemindedAt(Instant v) { this.remindedAt = v; }
    public boolean isBookRequested() { return bookRequested; }
    public void setBookRequested(boolean v) { this.bookRequested = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
