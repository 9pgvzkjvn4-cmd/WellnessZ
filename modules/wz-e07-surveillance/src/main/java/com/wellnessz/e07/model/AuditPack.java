package com.wellnessz.e07.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Generated audit pack (PDF + CSV) for IOSA / CAA / food safety / labour inspectorate audits. */
@Entity
@Table(name = "audit_pack", schema = "surveillance")
public class AuditPack {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "template")
    private String template;
    @Column(name = "programme_code")
    private String programmeCode;
    @Column(name = "from_date")
    private LocalDate fromDate;
    @Column(name = "to_date")
    private LocalDate toDate;
    @Column(name = "anonymised")
    private boolean anonymised;
    @Column(name = "pdf")
    private byte[] pdf;
    @Column(name = "csv", columnDefinition = "text")
    private String csv;
    @Column(name = "generated_by")
    private String generatedBy;
    @Column(name = "generated_at")
    private Instant generatedAt;

    public AuditPack() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getTemplate() { return template; }
    public void setTemplate(String v) { this.template = v; }
    public String getProgrammeCode() { return programmeCode; }
    public void setProgrammeCode(String v) { this.programmeCode = v; }
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate v) { this.fromDate = v; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate v) { this.toDate = v; }
    public boolean isAnonymised() { return anonymised; }
    public void setAnonymised(boolean v) { this.anonymised = v; }
    public byte[] getPdf() { return pdf; }
    public void setPdf(byte[] v) { this.pdf = v; }
    public String getCsv() { return csv; }
    public void setCsv(String v) { this.csv = v; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String v) { this.generatedBy = v; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant v) { this.generatedAt = v; }
}
