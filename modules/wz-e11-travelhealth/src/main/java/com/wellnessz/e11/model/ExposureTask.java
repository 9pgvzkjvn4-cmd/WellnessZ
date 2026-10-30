package com.wellnessz.e11.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Protocol timer (due at) or serology appointment (due on) of an exposure (E11-F03). The note is encrypted. */
@Entity(name = "E11ExposureTask")
@Table(name = "exposure_task", schema = "travelhealth")
public class ExposureTask {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "exposure_id")
    private UUID exposureId;
    @Column(name = "kind")
    private String kind;
    @Column(name = "code")
    private String code;
    @Column(name = "label")
    private String label;
    @Column(name = "position")
    private int position;
    @Column(name = "due_at")
    private Instant dueAt;
    @Column(name = "due_on")
    private LocalDate dueOn;
    @Column(name = "status")
    private String status;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Column(name = "completed_by")
    private String completedBy;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note", columnDefinition = "text")
    private String note;
    @Column(name = "case_task_id")
    private UUID caseTaskId;
    @Column(name = "due_soon_alerted_at")
    private Instant dueSoonAlertedAt;
    @Column(name = "overdue_alerted_at")
    private Instant overdueAlertedAt;

    /** New instance with a random id. */
    public static ExposureTask create() {
        ExposureTask e = new ExposureTask();
        e.id = UUID.randomUUID();
        return e;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID v) {
        this.id = v;
    }

    public UUID getExposureId() {
        return exposureId;
    }

    public void setExposureId(UUID v) {
        this.exposureId = v;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String v) {
        this.kind = v;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String v) {
        this.code = v;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String v) {
        this.label = v;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int v) {
        this.position = v;
    }

    public Instant getDueAt() {
        return dueAt;
    }

    public void setDueAt(Instant v) {
        this.dueAt = v;
    }

    public LocalDate getDueOn() {
        return dueOn;
    }

    public void setDueOn(LocalDate v) {
        this.dueOn = v;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String v) {
        this.status = v;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant v) {
        this.completedAt = v;
    }

    public String getCompletedBy() {
        return completedBy;
    }

    public void setCompletedBy(String v) {
        this.completedBy = v;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String v) {
        this.note = v;
    }

    public UUID getCaseTaskId() {
        return caseTaskId;
    }

    public void setCaseTaskId(UUID v) {
        this.caseTaskId = v;
    }

    public Instant getDueSoonAlertedAt() {
        return dueSoonAlertedAt;
    }

    public void setDueSoonAlertedAt(Instant v) {
        this.dueSoonAlertedAt = v;
    }

    public Instant getOverdueAlertedAt() {
        return overdueAlertedAt;
    }

    public void setOverdueAlertedAt(Instant v) {
        this.overdueAlertedAt = v;
    }
}
