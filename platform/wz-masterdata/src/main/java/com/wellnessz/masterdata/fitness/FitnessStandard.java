package com.wellnessz.masterdata.fitness;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Role fitness standard (E05-BL-001), versioned: a change creates a new version, older versions stay referenced by decisions. */
@Entity
@Table(name = "fitness_standard", schema = "masterdata")
@IdClass(FitnessStandard.Key.class)
public class FitnessStandard {

    public record Key(String code, int version) implements Serializable {
        public Key() {
            this(null, 0);
        }
    }

    @Id
    @Column(length = 40)
    private String code;
    @Id
    private int version;
    @Column(nullable = false, length = 200)
    private String title;
    @Column(name = "role_category", nullable = false, length = 40)
    private String roleCategory;
    @Column(nullable = false, length = 300)
    private String source;
    @Column(name = "reference_url", length = 500)
    private String referenceUrl;
    @Column(name = "validity_months", nullable = false)
    private int validityMonths;
    @Column(name = "validity_months_over_40")
    private Integer validityMonthsOver40;
    @Column(name = "requires_signoff_role", length = 20)
    private String requiresSignoffRole;
    @Column(name = "limitation_codes", length = 500)
    private String limitationCodes;
    @Column(nullable = false, length = 20)
    private String status;
    @Column(name = "updated_by", nullable = false, length = 200)
    private String updatedBy;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FitnessStandard() {
    }

    public FitnessStandard(String code, int version) {
        this.code = code;
        this.version = version;
    }

    public List<String> limitationCodeList() {
        return limitationCodes == null || limitationCodes.isBlank() ? List.of()
                : Arrays.stream(limitationCodes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public String getCode() { return code; }
    public int getVersion() { return version; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRoleCategory() { return roleCategory; }
    public void setRoleCategory(String roleCategory) { this.roleCategory = roleCategory; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getReferenceUrl() { return referenceUrl; }
    public void setReferenceUrl(String referenceUrl) { this.referenceUrl = referenceUrl; }
    public int getValidityMonths() { return validityMonths; }
    public void setValidityMonths(int validityMonths) { this.validityMonths = validityMonths; }
    public Integer getValidityMonthsOver40() { return validityMonthsOver40; }
    public void setValidityMonthsOver40(Integer v) { this.validityMonthsOver40 = v; }
    public String getRequiresSignoffRole() { return requiresSignoffRole; }
    public void setRequiresSignoffRole(String r) { this.requiresSignoffRole = r; }
    public String getLimitationCodes() { return limitationCodes; }
    public void setLimitationCodes(String l) { this.limitationCodes = l; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public boolean equals(Object o) {
        return o instanceof FitnessStandard f && Objects.equals(code, f.code) && version == f.version;
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, version);
    }
}
