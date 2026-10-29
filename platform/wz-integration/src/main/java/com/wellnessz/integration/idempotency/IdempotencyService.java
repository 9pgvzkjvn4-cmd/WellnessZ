package com.wellnessz.integration.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;

/**
 * Implements the IETF {@code Idempotency-Key} pattern for inbound APIs: the first request with a key is executed,
 * retries with the same key and payload receive the stored response, a different payload with the same key is
 * rejected with 422. An advisory lock prevents two concurrent first-requests from both executing.
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, JdbcTemplate jdbc, Clock clock) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public ResponseEntity<String> execute(String scope, String key, String requestBody, Supplier<ResponseEntity<String>> action) {
        if (key == null || !key.matches("[A-Za-z0-9._:\\-]{8,200}")) {
            throw ApiException.badRequest("idempotency_key_required", "A valid Idempotency-Key header (8-200 chars) is required");
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", scope + "/" + key);
        String hash = sha256(requestBody);
        Optional<IdempotencyRecord> existing = repository.findById(new IdempotencyRecord.Key(scope, key));
        if (existing.isPresent()) {
            IdempotencyRecord r = existing.get();
            if (!MessageDigest.isEqual(r.getRequestHash().getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII))) {
                throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused",
                        "Idempotency-Key was already used with a different payload");
            }
            return ResponseEntity.status(r.getResponseStatus()).header("Idempotent-Replayed", "true").body(r.getResponseBody());
        }
        ResponseEntity<String> response = action.get();
        repository.save(new IdempotencyRecord(scope, key, hash, response.getStatusCode().value(), response.getBody(), clock.instant()));
        return response;
    }

    @Scheduled(cron = "${wellnessz.integration.idempotency.purge-cron:0 15 3 * * *}")
    @Transactional
    public void purge() {
        repository.purgeOlderThan(clock.instant().minus(Duration.ofDays(7)));
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (s == null ? "" : s).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
