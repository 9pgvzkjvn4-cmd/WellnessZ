package com.wellnessz.security.consent;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** A consent decision. Withdrawal never deletes the record – it is kept as evidence. */
@Entity
@Table(name = "consent", schema = "platform")
public class Consent {

    public enum Status { GRANTED, WITHDRAWN }

    @Id
    private UUID id;
    @Column(name = "employee_id", nullable = false, length = 64)
    private String employeeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ConsentPurpose purpose;
    @Column(length = 500)
    private String scope;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;
    @Column(name = "notice_version", nullable = false, length = 20)
    private String noticeVersion;
    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
    @Column(name = "captured_by", nullable = false, length = 200)
    private String capturedBy;
    @Version
    private long version;

    protected Consent() {
    }

    public Consent(String employeeId, ConsentPurpose purpose, String scope, String noticeVersion, String capturedBy, Instant now) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.purpose = purpose;
        this.scope = scope;
        this.noticeVersion = noticeVersion;
        this.capturedBy = capturedBy;
        this.status = Status.GRANTED;
        this.grantedAt = now;
    }

    public void withdraw(Instant now) {
        this.status = Status.WITHDRAWN;
        this.withdrawnAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmployeeId() {
        return employeeId;
    }

    public ConsentPurpose getPurpose() {
        return purpose;
    }

    public String getScope() {
        return scope;
    }

    public Status getStatus() {
        return status;
    }

    public String getNoticeVersion() {
        return noticeVersion;
    }

    public Instant getGrantedAt() {
        return grantedAt;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public String getCapturedBy() {
        return capturedBy;
    }
}
