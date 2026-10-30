package com.wellnessz.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiRateLimitFilterTest {

    private final MutableClock clock = new MutableClock();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static WellnessZSecurityProperties props(int perMinute, int burst) {
        WellnessZSecurityProperties p = new WellnessZSecurityProperties();
        p.setRateLimitPerMinute(perMinute);
        p.setIntegrationRateLimitPerMinute(perMinute * 10);
        p.setRateLimitBurst(burst);
        return p;
    }

    private static void as(String user, String role) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private int call(ApiRateLimitFilter filter) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void allowsBurstThenReturns429WithRetryAfterAndRecovers() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props(60, 5), clock); // 1 request/s steady, burst 5 (+1)
        as("nurse1", "OH_CLINICIAN");
        for (int i = 0; i < 6; i++) {
            assertThat(call(filter)).isEqualTo(200);
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/me"), limited, new MockFilterChain());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("1");
        assertThat(limited.getContentAsString()).contains("rate_limited");
        clock.advance(1_000);
        assertThat(call(filter)).isEqualTo(200);
    }

    @Test
    void limitsArePerPrincipal() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props(60, 1), clock);
        as("nurse1", "OH_CLINICIAN");
        call(filter);
        call(filter);
        assertThat(call(filter)).isEqualTo(429);
        as("nurse2", "OH_CLINICIAN");
        assertThat(call(filter)).isEqualTo(200);
    }

    @Test
    void integrationClientsGetTheirOwnHigherLimit() throws Exception {
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props(60, 1), clock); // integration: 600/min -> burst 1 + 10
        as("lab-client", "INTEGRATION");
        for (int i = 0; i < 11; i++) {
            assertThat(call(filter)).isEqualTo(200);
        }
        assertThat(call(filter)).isEqualTo(429);
    }

    @Test
    void zeroDisablesAndAnonymousRequestsPassThrough() throws Exception {
        ApiRateLimitFilter disabled = new ApiRateLimitFilter(props(0, 0), clock);
        as("nurse1", "OH_CLINICIAN");
        for (int i = 0; i < 50; i++) {
            assertThat(call(disabled)).isEqualTo(200);
        }
        SecurityContextHolder.clearContext();
        ApiRateLimitFilter filter = new ApiRateLimitFilter(props(60, 1), clock);
        for (int i = 0; i < 10; i++) {
            assertThat(call(filter)).isEqualTo(200); // authorisation, not the limiter, rejects anonymous callers
        }
    }

    private static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-10-30T08:00:00Z").toEpochMilli();

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
