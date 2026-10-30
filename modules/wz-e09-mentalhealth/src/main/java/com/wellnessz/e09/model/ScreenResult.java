package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Scored validated screen (PHQ-9 / GAD-7), insert-only. */
@Entity(name = "E09ScreenResult")
@Table(name = "screen_result", schema = "mentalhealth")
public class ScreenResult {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "instrument")
    private String instrument;
    @Column(name = "answers")
    private String answers;
    @Column(name = "score")
    private int score;
    @Column(name = "band")
    private String band;
    @Column(name = "urgent")
    private boolean urgent;
    @Column(name = "administered_by")
    private String administeredBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public ScreenResult() {
        // JPA; populated through setters by the owning service
    }

    public static ScreenResult create() {
        ScreenResult x = new ScreenResult();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getInstrument() { return instrument; }
    public void setInstrument(String v) { this.instrument = v; }
    public String getAnswers() { return answers; }
    public void setAnswers(String v) { this.answers = v; }
    public int getScore() { return score; }
    public void setScore(int v) { this.score = v; }
    public String getBand() { return band; }
    public void setBand(String v) { this.band = v; }
    public boolean isUrgent() { return urgent; }
    public void setUrgent(boolean v) { this.urgent = v; }
    public String getAdministeredBy() { return administeredBy; }
    public void setAdministeredBy(String v) { this.administeredBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
