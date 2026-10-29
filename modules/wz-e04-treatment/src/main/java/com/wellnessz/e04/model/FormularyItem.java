package com.wellnessz.e04.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Clinic formulary item (E04-F05). */
@Entity(name = "TxFormularyItem")
@Table(name = "formulary_item", schema = "treatment")
public class FormularyItem {

    @Id
    @Column(name = "code")
    private String code;
    @Column(name = "name")
    private String name;
    @Column(name = "form")
    private String form;
    @Column(name = "strength")
    private String strength;
    @Column(name = "unit")
    private String unit;
    @Column(name = "routes")
    private String routes;
    @Column(name = "snomed_code")
    private String snomedCode;
    @Column(name = "stock_tracked")
    private boolean stockTracked;
    @Column(name = "reorder_level")
    private int reorderLevel;
    @Column(name = "active")
    private boolean active;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public FormularyItem() {
        // JPA; populated through setters by the owning service
    }

    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getForm() { return form; }
    public void setForm(String v) { this.form = v; }
    public String getStrength() { return strength; }
    public void setStrength(String v) { this.strength = v; }
    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }
    public String getRoutes() { return routes; }
    public void setRoutes(String v) { this.routes = v; }
    public String getSnomedCode() { return snomedCode; }
    public void setSnomedCode(String v) { this.snomedCode = v; }
    public boolean isStockTracked() { return stockTracked; }
    public void setStockTracked(boolean v) { this.stockTracked = v; }
    public int getReorderLevel() { return reorderLevel; }
    public void setReorderLevel(int v) { this.reorderLevel = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
