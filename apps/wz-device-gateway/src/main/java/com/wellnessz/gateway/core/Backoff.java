package com.wellnessz.gateway.core;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * Exponential back-off with "equal jitter": delay = d/2 + random(0, d/2) where d = min(max, initial · 2^attempt).
 * A server-provided {@code Retry-After} wins when it is longer. Thread-confined (one per retry loop).
 */
public final class Backoff {

    private final Duration initial;
    private final Duration max;
    private final RandomGenerator random;
    private int attempt;

    public Backoff(Duration initial, Duration max, RandomGenerator random) {
        if (initial.isNegative() || initial.isZero() || max.compareTo(initial) < 0) {
            throw new IllegalArgumentException("back-off requires 0 < initial <= max");
        }
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    /** Delay before the next attempt; increments the attempt counter. */
    public Duration next(Duration retryAfter) {
        long ceiling = ceilingMillis(attempt);
        attempt = Math.min(attempt + 1, 62);
        long half = ceiling / 2;
        long delay = ceiling - (half > 0 ? random.nextLong(half + 1) : 0); // in [ceiling - half, ceiling], never 0
        if (retryAfter != null && retryAfter.toMillis() > delay) {
            delay = retryAfter.toMillis();
        }
        return Duration.ofMillis(delay);
    }

    public Duration next() {
        return next(null);
    }

    public void reset() {
        attempt = 0;
    }

    public int attempts() {
        return attempt;
    }

    long ceilingMillis(int n) {
        long init = initial.toMillis();
        long cap = max.toMillis();
        if (n >= 62 || init > (cap >> Math.min(n, 62))) {
            return cap;
        }
        return Math.min(cap, init << n);
    }

    /** Parses {@code Retry-After} given in seconds; HTTP-date values are ignored (treated as absent). */
    public static Duration parseRetryAfter(String value) {
        if (value == null) {
            return null;
        }
        try {
            long s = Long.parseLong(value.trim());
            return s >= 0 ? Duration.ofSeconds(Math.min(s, 3600)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
