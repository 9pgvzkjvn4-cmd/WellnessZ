package com.wellnessz.masterdata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * No-code configuration (E18): feature flags, module enablement, SLA values, form/workflow definitions as JSON.
 * Every change is versioned (optimistic locking) and audited.
 */
@Entity
@Table(name = "config_entry", schema = "masterdata")
public class ConfigEntry {

    @Id
    @Column(name = "config_key", length = 200)
    private String key;
    @Column(name = "config_value", nullable = false, columnDefinition = "text")
    private String value;
    @Column(length = 500)
    private String description;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected ConfigEntry() {
    }

    public ConfigEntry(String key, String value, String description, String updatedBy, Instant updatedAt) {
        this.key = key;
        this.value = value;
        this.description = description;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public void change(String value, String updatedBy, Instant now) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = now;
    }

    public String getKey() {
        return key;
    }

    public String getValue() {
        return value;
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

    public long getVersion() {
        return version;
    }
}
