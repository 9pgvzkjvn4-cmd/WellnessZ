package com.wellnessz.ehsintake.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Import batch for traceability: which file / e-mail produced which incidents, with row-level error summary. */
@Entity
@Table(name = "import_batch", schema = "intake")
public class ImportBatch {

    @Id
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntakeChannel channel;
    @Column(name = "source_name", length = 300)
    private String sourceName;
    @Column(name = "content_sha256", length = 64)
    private String contentSha256;
    @Column(name = "rows_total", nullable = false)
    private int rowsTotal;
    @Column(name = "rows_accepted", nullable = false)
    private int rowsAccepted;
    @Column(name = "rows_duplicate", nullable = false)
    private int rowsDuplicate;
    @Column(name = "rows_rejected", nullable = false)
    private int rowsRejected;
    @Column(columnDefinition = "text")
    private String errors;
    @Column(name = "created_by", nullable = false, length = 200)
    private String createdBy;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportBatch() {
    }

    public ImportBatch(IntakeChannel channel, String sourceName, String contentSha256, String createdBy, Instant now) {
        this.id = UUID.randomUUID();
        this.channel = channel;
        this.sourceName = sourceName;
        this.contentSha256 = contentSha256;
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    public void complete(int total, int accepted, int duplicate, int rejected, String errors) {
        this.rowsTotal = total;
        this.rowsAccepted = accepted;
        this.rowsDuplicate = duplicate;
        this.rowsRejected = rejected;
        this.errors = errors;
    }

    public UUID getId() {
        return id;
    }

    public IntakeChannel getChannel() {
        return channel;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public int getRowsTotal() {
        return rowsTotal;
    }

    public int getRowsAccepted() {
        return rowsAccepted;
    }

    public int getRowsDuplicate() {
        return rowsDuplicate;
    }

    public int getRowsRejected() {
        return rowsRejected;
    }

    public String getErrors() {
        return errors;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
