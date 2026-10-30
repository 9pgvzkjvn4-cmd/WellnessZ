package com.wellnessz.e12.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ergonomic workstation / manual-handling / task assessment (E12-F03). */
@Entity(name = "E12ErgonomicAssessment")
@Table(name = "ergonomic_assessment", schema = "promotion")
public class ErgonomicAssessment {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "assessment_ref")
    private String assessmentRef;
    @Column(name = "assessment_type")
    private String assessmentType;
    @Column(name = "unit_code")
    private String unitCode;
    @Column(name = "job_code")
    private String jobCode;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "location")
    private String location;
    @Column(name = "checklist", columnDefinition = "text")
    private String checklist;
    @Column(name = "findings", columnDefinition = "text")
    private String findings;
    @Column(name = "risk_score")
    private int riskScore;
    @Column(name = "rag")
    private String rag;
    @Column(name = "job_demand", columnDefinition = "text")
    private String jobDemand;
    @Column(name = "assessed_by")
    private String assessedBy;
    @Column(name = "assessed_at")
    private Instant assessedAt;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ErgonomicAssessment() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getAssessmentRef() { return assessmentRef; }
    public void setAssessmentRef(String v) { this.assessmentRef = v; }
    public String getAssessmentType() { return assessmentType; }
    public void setAssessmentType(String v) { this.assessmentType = v; }
    public String getUnitCode() { return unitCode; }
    public void setUnitCode(String v) { this.unitCode = v; }
    public String getJobCode() { return jobCode; }
    public void setJobCode(String v) { this.jobCode = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getChecklist() { return checklist; }
    public void setChecklist(String v) { this.checklist = v; }
    public String getFindings() { return findings; }
    public void setFindings(String v) { this.findings = v; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int v) { this.riskScore = v; }
    public String getRag() { return rag; }
    public void setRag(String v) { this.rag = v; }
    public String getJobDemand() { return jobDemand; }
    public void setJobDemand(String v) { this.jobDemand = v; }
    public String getAssessedBy() { return assessedBy; }
    public void setAssessedBy(String v) { this.assessedBy = v; }
    public Instant getAssessedAt() { return assessedAt; }
    public void setAssessedAt(Instant v) { this.assessedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
