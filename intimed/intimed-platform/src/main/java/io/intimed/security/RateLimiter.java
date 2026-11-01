package io.intimed.security;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket rate limiting and quotas per connector, endpoint and tenant (SC-13, IM-E01-HLR-13). Exhausted buckets
 * answer 429 / MLLP AR and raise an anomaly signal.
 */
@Component
public class RateLimiter {
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        final double rate, capacity;
        double tokens;
        long last = System.nanoTime();
        Bucket(double rate, double capacity) { this.rate = rate; this.capacity = capacity; this.tokens = capacity; }
        synchronized boolean take() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) / 1e9 * rate);
            last = now;
            if (tokens >= 1) { tokens -= 1; return true; }
            return false;
        }
    }

    public boolean allow(String key, double perSecond, double burst) {
        return buckets.computeIfAbsent(key + "@" + perSecond + "/" + burst, k -> new Bucket(perSecond, burst)).take();
    }

    public void reset() { buckets.clear(); }
}
