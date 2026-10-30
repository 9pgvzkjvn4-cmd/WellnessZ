package com.wellnessz.e10.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Auditable random selection: seed commitment, pool snapshot and the drawn employees (E10-BL-001). */
@Entity(name = "E10Selection")
@Table(name = "selection", schema = "substance")
public class Selection {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "ref")
    private String ref;
    @Column(name = "role_group")
    private String roleGroup;
    @Column(name = "period")
    private String period;
    @Column(name = "test_type")
    private String testType;
    @Column(name = "rate_pct")
    private BigDecimal ratePct;
    @Column(name = "pool_size")
    private int poolSize;
    @Column(name = "pool_hash")
    private String poolHash;
    @Column(name = "pool_snapshot", columnDefinition = "text")
    private String poolSnapshot;
    @Column(name = "seed_hex")
    private String seedHex;
    @Column(name = "seed_commitment")
    private String seedCommitment;
    @Column(name = "selected_count")
    private int selectedCount;
    @Column(name = "selected", columnDefinition = "text")
    private String selected;
    @Column(name = "policy_version")
    private long policyVersion;
    @Column(name = "policy_approval")
    private String policyApproval;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;
    @Column(name = "notified_at")
    private Instant notifiedAt;
    @Column(name = "notified_by")
    private String notifiedBy;

    public Selection() {
        // JPA; populated through setters by the owning service
    }

    public static Selection create() {
        Selection x = new Selection();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getRef() { return ref; }
    public void setRef(String v) { this.ref = v; }
    public String getRoleGroup() { return roleGroup; }
    public void setRoleGroup(String v) { this.roleGroup = v; }
    public String getPeriod() { return period; }
    public void setPeriod(String v) { this.period = v; }
    public String getTestType() { return testType; }
    public void setTestType(String v) { this.testType = v; }
    public BigDecimal getRatePct() { return ratePct; }
    public void setRatePct(BigDecimal v) { this.ratePct = v; }
    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int v) { this.poolSize = v; }
    public String getPoolHash() { return poolHash; }
    public void setPoolHash(String v) { this.poolHash = v; }
    public String getPoolSnapshot() { return poolSnapshot; }
    public void setPoolSnapshot(String v) { this.poolSnapshot = v; }
    public String getSeedHex() { return seedHex; }
    public void setSeedHex(String v) { this.seedHex = v; }
    public String getSeedCommitment() { return seedCommitment; }
    public void setSeedCommitment(String v) { this.seedCommitment = v; }
    public int getSelectedCount() { return selectedCount; }
    public void setSelectedCount(int v) { this.selectedCount = v; }
    public String getSelected() { return selected; }
    public void setSelected(String v) { this.selected = v; }
    public long getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(long v) { this.policyVersion = v; }
    public String getPolicyApproval() { return policyApproval; }
    public void setPolicyApproval(String v) { this.policyApproval = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(Instant v) { this.notifiedAt = v; }
    public String getNotifiedBy() { return notifiedBy; }
    public void setNotifiedBy(String v) { this.notifiedBy = v; }
}
