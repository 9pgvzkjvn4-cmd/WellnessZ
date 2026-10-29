package com.wellnessz.e08.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Monthly ERP finance export (units, categories, amounts – no names) with its SHA-256 checksum. */
@Entity(name = "E08CostExport")
@Table(name = "cost_export", schema = "claims")
public class CostExport {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "export_month")
    private String exportMonth;
    @Column(name = "batch_id")
    private String batchId;
    @Column(name = "row_count")
    private int rowCount;
    @Column(name = "total")
    private BigDecimal total;
    @Column(name = "currency")
    private String currency;
    @Column(name = "checksum")
    private String checksum;
    @Column(name = "status")
    private String status;
    @Column(name = "content", columnDefinition = "text")
    private String content;
    @Column(name = "erp_total")
    private BigDecimal erpTotal;
    @Column(name = "erp_reference")
    private String erpReference;
    @Column(name = "reconciled_at")
    private Instant reconciledAt;
    @Column(name = "reconciled_by")
    private String reconciledBy;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at")
    private Instant createdAt;

    public CostExport() {
        // JPA; populated through setters by the owning service
    }

    public static CostExport create() {
        CostExport x = new CostExport();
        x.id = UUID.randomUUID();
        return x;
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getExportMonth() { return exportMonth; }
    public void setExportMonth(String v) { this.exportMonth = v; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String v) { this.batchId = v; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int v) { this.rowCount = v; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal v) { this.total = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String v) { this.checksum = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public BigDecimal getErpTotal() { return erpTotal; }
    public void setErpTotal(BigDecimal v) { this.erpTotal = v; }
    public String getErpReference() { return erpReference; }
    public void setErpReference(String v) { this.erpReference = v; }
    public Instant getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(Instant v) { this.reconciledAt = v; }
    public String getReconciledBy() { return reconciledBy; }
    public void setReconciledBy(String v) { this.reconciledBy = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
