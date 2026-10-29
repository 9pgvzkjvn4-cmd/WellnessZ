package com.wellnessz.security.breakglass;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.wellnessz.secrets.EncryptedStringConverter;

/** Time-limited emergency access to an employee's restricted-class record (E03-BL-021), reviewed by the DPO. */
@Entity
@Table(name = "break_glass_grant", schema = "platform")
public class BreakGlassGrant {

    public enum Review { PENDING, APPROPRIATE, INAPPROPRIATE }

    @Id
    private UUID id;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Column(name = "granted_to", nullable = false, length = 200)
    private String grantedTo;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private String reason;
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Review review;
    @Column(name = "review_due_at", nullable = false)
    private Instant reviewDueAt;
    @Column(name = "reviewed_by", length = 200)
    private String reviewedBy;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;

    protected BreakGlassGrant() {
    }

    public BreakGlassGrant(String employeeId, String grantedTo, String reason, Instant now, Instant expiresAt, Instant reviewDueAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.grantedTo = grantedTo;
        this.reason = reason;
        this.grantedAt = now;
        this.expiresAt = expiresAt;
        this.review = Review.PENDING;
        this.reviewDueAt = reviewDueAt;
    }

    public void review(Review outcome, String by, String note, Instant now) {
        this.review = outcome;
        this.reviewedBy = by;
        this.reviewNote = note;
        this.reviewedAt = now;
    }

    public UUID getId() { return id; }
    public String getEmployeeId() { return employeeId; }
    public String getGrantedTo() { return grantedTo; }
    public String getReason() { return reason; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Review getReview() { return review; }
    public Instant getReviewDueAt() { return reviewDueAt; }
    public String getReviewedBy() { return reviewedBy; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReviewNote() { return reviewNote; }
}
