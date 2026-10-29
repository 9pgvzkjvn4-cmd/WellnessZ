package com.wellnessz.masterdata;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Node of the airline organisation hierarchy, synchronised from HRIS/HCM. {@code path} is materialised for scope checks. */
@Entity
@Table(name = "org_unit", schema = "masterdata")
public class OrgUnit {

    @Id
    @Column(length = 64)
    private String code;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(name = "parent_code", length = 64)
    private String parentCode;
    @Column(nullable = false, length = 1000)
    private String path;
    @Column(name = "unit_type", nullable = false, length = 40)
    private String unitType;
    @Column(nullable = false)
    private boolean active = true;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrgUnit() {
    }

    public OrgUnit(String code, String name, String parentCode, String path, String unitType, Instant updatedAt) {
        this.code = code;
        this.name = name;
        this.parentCode = parentCode;
        this.path = path;
        this.unitType = unitType;
        this.updatedAt = updatedAt;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getParentCode() {
        return parentCode;
    }

    public String getPath() {
        return path;
    }

    public String getUnitType() {
        return unitType;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String name, String parentCode, String path, String unitType, boolean active, Instant now) {
        this.name = name;
        this.parentCode = parentCode;
        this.path = path;
        this.unitType = unitType;
        this.active = active;
        this.updatedAt = now;
    }
}
