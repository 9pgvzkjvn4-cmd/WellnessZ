package com.wellnessz.e01.recordability;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;

/** Local legal recordability / reporting category per class (E01-BL-022), signed off by CMO and HSE Head. */
@Entity
@Table(name = "recordability_mapping", schema = "intake")
public class RecordabilityMapping {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private Recordability recordability;
    @Column(name = "local_category", nullable = false, length = 100)
    private String localCategory;
    @Column(name = "caa_reportable", nullable = false)
    private boolean caaReportable;
    @Column(length = 500)
    private String description;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecordabilityMapping() {
    }

    public void update(String localCategory, boolean caaReportable, String description, String actor, Instant now) {
        this.localCategory = localCategory;
        this.caaReportable = caaReportable;
        this.description = description;
        this.updatedBy = actor;
        this.updatedAt = now;
    }

    public Recordability getRecordability() {
        return recordability;
    }

    public String getLocalCategory() {
        return localCategory;
    }

    public boolean isCaaReportable() {
        return caaReportable;
    }

    public String getDescription() {
        return description;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
