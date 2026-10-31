package com.wellnessz.e33.model;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Verification, sign-off and release rule of one bound OH form (clinical-governance configuration, V24 seed). */
@Entity(name = "E33FormRule")
@Table(name = "form_rule", schema = "devicecap")
public class FormRule {

    @Id
    @Column(name = "form_code")
    private String formCode;
    @Column(name = "form_version")
    private String formVersion;
    @Column(name = "module")
    private String module;
    @Column(name = "category")
    private String category;
    @Column(name = "target")
    private String target;
    @Column(name = "verifier_roles")
    private String verifierRoles;
    @Column(name = "sign_off_roles")
    private String signOffRoles;
    @Column(name = "verify_always")
    private boolean verifyAlways;
    @Column(name = "release_to_employee")
    private boolean releaseToEmployee;
    @Column(name = "restricted")
    private boolean restricted;
    @Column(name = "minutes_saved")
    private BigDecimal minutesSaved;
    @Column(name = "updated_by")
    private String updatedBy;
    @Column(name = "updated_at")
    private Instant updatedAt;

    public FormRule() {
        // JPA
    }

    public String getFormCode() { return formCode; }
    public void setFormCode(String v) { this.formCode = v; }
    public String getFormVersion() { return formVersion; }
    public void setFormVersion(String v) { this.formVersion = v; }
    public String getModule() { return module; }
    public void setModule(String v) { this.module = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getTarget() { return target; }
    public void setTarget(String v) { this.target = v; }
    public String getVerifierRoles() { return verifierRoles; }
    public void setVerifierRoles(String v) { this.verifierRoles = v; }
    public String getSignOffRoles() { return signOffRoles; }
    public void setSignOffRoles(String v) { this.signOffRoles = v; }
    public boolean isVerifyAlways() { return verifyAlways; }
    public void setVerifyAlways(boolean v) { this.verifyAlways = v; }
    public boolean isReleaseToEmployee() { return releaseToEmployee; }
    public void setReleaseToEmployee(boolean v) { this.releaseToEmployee = v; }
    public boolean isRestricted() { return restricted; }
    public void setRestricted(boolean v) { this.restricted = v; }
    public BigDecimal getMinutesSaved() { return minutesSaved; }
    public void setMinutesSaved(BigDecimal v) { this.minutesSaved = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
