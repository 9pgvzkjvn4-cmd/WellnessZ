package com.wellnessz.e09.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Quarterly anonymised programme report published to the safety management system. */
@Entity(name = "E09SmsReport")
@Table(name = "sms_report", schema = "mentalhealth")
public class SmsReport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "quarter")
    private String quarter;
    @Column(name = "report_ref")
    private String reportRef;
    @Column(name = "cells")
    private int cells;
    @Column(name = "sent_by")
    private String sentBy;
    @Column(name = "sent_at")
    private Instant sentAt;

    public SmsReport() {
        // JPA; populated through setters by the owning service
    }

    public static SmsReport create() {
        SmsReport x = new SmsReport();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getQuarter() { return quarter; }
    public void setQuarter(String v) { this.quarter = v; }
    public String getReportRef() { return reportRef; }
    public void setReportRef(String v) { this.reportRef = v; }
    public int getCells() { return cells; }
    public void setCells(int v) { this.cells = v; }
    public String getSentBy() { return sentBy; }
    public void setSentBy(String v) { this.sentBy = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
}
