package com.wellnessz.masterdata.fitness;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Restriction catalogue entry (E05-HLR-02) – functional wording only, never a diagnosis. */
@Entity
@Table(name = "restriction", schema = "masterdata")
public class Restriction {

    @Id
    @Column(length = 40)
    private String code;
    @Column(nullable = false, length = 200)
    private String label;
    @Column(nullable = false, length = 40)
    private String dimension;
    @Column(name = "value_required", nullable = false)
    private boolean valueRequired;
    @Column(length = 20)
    private String unit;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Restriction() {
    }

    public Restriction(String code) {
        this.code = code;
    }

    /** Label with the value filled in, e.g. "No lifting above 10 kg". */
    public String render(Number value) {
        return label.replace("{value}", value == null ? "?" : stripZeros(value));
    }

    private static String stripZeros(Number n) {
        double d = n.doubleValue();
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDimension() { return dimension; }
    public void setDimension(String dimension) { this.dimension = dimension; }
    public boolean isValueRequired() { return valueRequired; }
    public void setValueRequired(boolean valueRequired) { this.valueRequired = valueRequired; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
