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

/** Milestone of a long-term absence pathway. */
@Entity
@Table(name = "lta_milestone", schema = "absence")
public class LtaMilestone {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "case_id")
    private UUID caseId;
    @Column(name = "week")
    private int week;
    @Column(name = "milestone_type")
    private String milestoneType;
    @Column(name = "due_date")
    private LocalDate dueDate;
    @Column(name = "status")
    private String status;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "completed_by")
    private String completedBy;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "overdue_notified_at")
    private Instant overdueNotifiedAt;
    @Column(name = "appointment_id")
    private UUID appointmentId;

    public LtaMilestone() {
        // JPA; populated through setters by the owning service
    }

    public static LtaMilestone create() {
        LtaMilestone x = new LtaMilestone();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID v) { this.caseId = v; }
    public int getWeek() { return week; }
    public void setWeek(int v) { this.week = v; }
    public String getMilestoneType() { return milestoneType; }
    public void setMilestoneType(String v) { this.milestoneType = v; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate v) { this.dueDate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getOverdueNotifiedAt() { return overdueNotifiedAt; }
    public void setOverdueNotifiedAt(Instant v) { this.overdueNotifiedAt = v; }
    public UUID getAppointmentId() { return appointmentId; }
    public void setAppointmentId(UUID v) { this.appointmentId = v; }
}
