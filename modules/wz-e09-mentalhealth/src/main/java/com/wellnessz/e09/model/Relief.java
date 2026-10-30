package com.wellnessz.e09.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Temporary relief from duties – status-only roster feed through the kernel case. */
@Entity(name = "E09Relief")
@Table(name = "relief", schema = "mentalhealth")
public class Relief {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "support_case_id")
    private UUID supportCaseId;
    @Column(name = "employee_id")
    private String employeeId;
    @Column(name = "from_date")
    private LocalDate fromDate;
    @Column(name = "expected_until")
    private LocalDate expectedUntil;
    @Column(name = "status")
    private String status;
    @Column(name = "decision")
    private String decision;
    @Column(name = "decided_by")
    private String decidedBy;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Version
    @Column(name = "version")
    private Long version;

    public Relief() {
        // JPA; populated through setters by the owning service
    }

    public static Relief create() {
        Relief x = new Relief();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getSupportCaseId() { return supportCaseId; }
    public void setSupportCaseId(UUID v) { this.supportCaseId = v; }
    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String v) { this.employeeId = v; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate v) { this.fromDate = v; }
    public LocalDate getExpectedUntil() { return expectedUntil; }
    public void setExpectedUntil(LocalDate v) { this.expectedUntil = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Long getVersion() { return version; }
}
