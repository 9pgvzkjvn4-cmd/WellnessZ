package com.wellnessz.e31.support;

import java.util.Map;

import org.springframework.http.HttpStatus;

import com.wellnessz.common.error.ApiException;

/** 429 {@code rate_limited} with the number of seconds for {@code Retry-After} (tenant quota, per-connector webhook limit). */
public class RateLimitedException extends ApiException {

    private static final long serialVersionUID = 1L;
    private final int retryAfterSeconds;

    public RateLimitedException(String message, int retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    @Override
    public Map<String, Object> properties() {
        return Map.of("retryAfterSeconds", retryAfterSeconds);
    }
}
