package com.wellnessz.integration.idempotency;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Stored outcome of an inbound request keyed by the caller's {@code Idempotency-Key}. */
@Entity
@Table(name = "idempotency_record", schema = "platform")
@IdClass(IdempotencyRecord.Key.class)
public class IdempotencyRecord {

    public record Key(String scope, String idempotencyKey) implements java.io.Serializable {
        public Key {
        }

        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(length = 100)
    private String scope;
    @Id
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;
    @Column(name = "response_status", nullable = false)
    private int responseStatus;
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String scope, String key, String requestHash, int responseStatus, String responseBody, Instant now) {
        this.scope = scope;
        this.idempotencyKey = key;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = now;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }
}
