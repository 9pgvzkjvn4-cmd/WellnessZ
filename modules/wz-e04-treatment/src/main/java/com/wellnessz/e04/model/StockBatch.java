package com.wellnessz.e04.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Medication stock batch held at a clinic site. */
@Entity(name = "TxStockBatch")
@Table(name = "stock_batch", schema = "treatment")
public class StockBatch {

    @Id
    @Column(name = "id")
    private UUID id;
    @Column(name = "item_code")
    private String itemCode;
    @Column(name = "batch_number")
    private String batchNumber;
    @Column(name = "expiry")
    private LocalDate expiry;
    @Column(name = "quantity")
    private int quantity;
    @Column(name = "site_code")
    private String siteCode;
    @Column(name = "received_by")
    private String receivedBy;
    @Column(name = "received_at")
    private Instant receivedAt;

    public StockBatch() {
        // JPA; populated through setters by the owning service
    }

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public String getItemCode() { return itemCode; }
    public void setItemCode(String v) { this.itemCode = v; }
    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String v) { this.batchNumber = v; }
    public LocalDate getExpiry() { return expiry; }
    public void setExpiry(LocalDate v) { this.expiry = v; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }
    public String getSiteCode() { return siteCode; }
    public void setSiteCode(String v) { this.siteCode = v; }
    public String getReceivedBy() { return receivedBy; }
    public void setReceivedBy(String v) { this.receivedBy = v; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }
}
