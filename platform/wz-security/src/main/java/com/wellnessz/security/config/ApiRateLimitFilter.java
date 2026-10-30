package com.wellnessz.security.config;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Per-principal token-bucket rate limit for the core API (OWASP API4 – unrestricted resource consumption).
 * Defence in depth behind the edge WAF rate rules, and the only limit in hybrid / on-premises deployments without a
 * cloud WAF. Buckets are per pod and keyed by the authenticated subject, so one user or client cannot exhaust the
 * service for everybody else. Exceeding the limit returns {@code 429} with {@code Retry-After}.
 */
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_TRACKED = 50_000;
    private static final long IDLE_EVICT_MILLIS = 10 * 60_000L;

    private final WellnessZSecurityProperties props;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(WellnessZSecurityProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            chain.doFilter(request, response); // anonymous requests are rejected by authorisation anyway
            return;
        }
        boolean machine = auth.getAuthorities().stream().anyMatch(a -> "ROLE_INTEGRATION".equals(a.getAuthority()));
        int perMinute = machine ? props.getIntegrationRateLimitPerMinute() : props.getRateLimitPerMinute();
        if (perMinute <= 0) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.millis();
        if (buckets.size() > MAX_TRACKED) {
            buckets.values().removeIf(b -> now - b.lastSeen > IDLE_EVICT_MILLIS);
        }
        Bucket bucket = buckets.computeIfAbsent(auth.getName(), k -> new Bucket(capacity(perMinute), now));
        long waitMillis = bucket.tryConsume(now, capacity(perMinute), perMinute / 60_000.0);
        if (waitMillis > 0) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(Math.max(1, (waitMillis + 999) / 1000)));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"code\":\"rate_limited\",\"detail\":\"Request rate limit exceeded; retry later\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private double capacity(int perMinute) {
        return Math.max(1, props.getRateLimitBurst()) + perMinute / 60.0;
    }

    /** Token bucket refilled continuously at the steady rate. */
    static final class Bucket {
        private double tokens;
        private long refilledAt;
        private volatile long lastSeen;

        Bucket(double capacity, long now) {
            this.tokens = capacity;
            this.refilledAt = now;
            this.lastSeen = now;
        }

        /** Takes one token; returns 0 when allowed, otherwise the milliseconds until a token is available. */
        synchronized long tryConsume(long now, double capacity, double tokensPerMilli) {
            lastSeen = now;
            tokens = Math.min(capacity, tokens + Math.max(0, now - refilledAt) * tokensPerMilli);
            refilledAt = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / tokensPerMilli);
        }
    }
}
