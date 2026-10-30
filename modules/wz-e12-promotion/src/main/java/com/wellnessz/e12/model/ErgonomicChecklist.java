package com.wellnessz.e12.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Ergonomic checklist template (items + weights + RAG bands). */
@Entity(name = "E12ErgonomicChecklist")
@Table(name = "ergonomic_checklist", schema = "promotion")
public class ErgonomicChecklist {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "title")
    private String title;
    @Column(name = "items", columnDefinition = "text")
    private String items;
    @Column(name = "amber_from")
    private int amberFrom;
    @Column(name = "red_from")
    private int redFrom;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public ErgonomicChecklist() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getItems() { return items; }
    public void setItems(String v) { this.items = v; }
    public int getAmberFrom() { return amberFrom; }
    public void setAmberFrom(int v) { this.amberFrom = v; }
    public int getRedFrom() { return redFrom; }
    public void setRedFrom(int v) { this.redFrom = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
