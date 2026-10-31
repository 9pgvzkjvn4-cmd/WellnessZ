package com.wellnessz.casekernel.device.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.casekernel.device.DeviceSecurityEventPublisher.DeviceSecurityEvent;

class DeviceEndpointAuthenticationFilterTest {

    static final String THUMB = "ab".repeat(32);
    final List<DeviceSecurityEvent> events = new ArrayList<>();
    final AtomicReference<DeviceEndpointAuthenticator.Credentials> seen = new AtomicReference<>();
    final DeviceEndpointAuthenticator e34 = c -> {
        seen.set(c);
        return "EP-LHR-GW-01".equals(c.endpointId()) && "secret-token".equals(c.token()) && THUMB.equals(c.certificateSha256())
                ? DeviceEndpointAuthenticator.Result.success(new DeviceEndpointPrincipal(c.endpointId(), DeviceCodes.EndpointKind.GATEWAY,
                        "LHR", "GATEWAY_ZONE", "GROUP", Set.of(), c.certificateSha256()))
                : DeviceEndpointAuthenticator.Result.failure("bad_token");
    };
    final Clock clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC);

    DeviceEndpointAuthenticationFilter filter(DeviceEndpointAuthenticator authenticator, boolean requireCert) {
        return new DeviceEndpointAuthenticationFilter(() -> authenticator, () -> events::add, "X-Client-Cert-Sha256", requireCert, clock);
    }

    static MockHttpServletRequest ingest() {
        MockHttpServletRequest r = new MockHttpServletRequest("POST", "/api/v1/device-ingest/hl7v2");
        r.setRequestURI("/api/v1/device-ingest/hl7v2");
        return r;
    }

    @Test
    void bothFactorsAuthenticateAndThePrincipalIsAvailableDownstream() throws Exception {
        MockHttpServletRequest req = ingest();
        req.addHeader("X-Device-Endpoint", "EP-LHR-GW-01");
        req.addHeader("Authorization", "DeviceToken secret-token");
        req.addHeader("X-Client-Cert-Sha256", THUMB.toUpperCase().replaceAll("(..)(?!$)", "$1:"));
        AtomicReference<Authentication> inChain = new AtomicReference<>();
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(e34, true).doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                inChain.set(SecurityContextHolder.getContext().getAuthentication());
                assertThat(DeviceEndpointPrincipal.current()).get().extracting(DeviceEndpointPrincipal::endpointId).isEqualTo("EP-LHR-GW-01");
            }
        });
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(inChain.get().getName()).isEqualTo("device:EP-LHR-GW-01");
        assertThat(inChain.get().getAuthorities()).extracting(Object::toString).contains("ROLE_DEVICE_ENDPOINT", "ENDPOINT_KIND_GATEWAY");
        assertThat(inChain.get().getCredentials()).isEqualTo("");
        assertThat(seen.get().certificateSha256()).isEqualTo(THUMB);
        assertThat(seen.get().toString()).doesNotContain("secret-token");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(events).isEmpty();
    }

    @Test
    void missingClientCertificateIsRefusedWhenMtlsIsRequired() throws Exception {
        MockHttpServletRequest req = ingest();
        req.addHeader("X-Device-Endpoint", "EP-LHR-GW-01");
        req.addHeader("Authorization", "DeviceToken secret-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(e34, true).doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"code\":\"mtls_required\"");
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("ENDPOINT_AUTH_FAILED");
            assertThat(e.reasonCode()).isEqualTo("mtls_required");
            assertThat(e.endpointId()).isEqualTo("EP-LHR-GW-01");
        });
    }

    @Test
    void wrongTokenMissingCredentialsAndHostileEndpointIdsAreRejectedWithOneEventEach() throws Exception {
        MockHttpServletRequest bad = ingest();
        bad.addHeader("X-Device-Endpoint", "EP-LHR-GW-01");
        bad.addHeader("Authorization", "DeviceToken wrong");
        bad.addHeader("X-Client-Cert-Sha256", THUMB);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(e34, true).doFilter(bad, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("bad_token").doesNotContain("wrong");

        MockHttpServletRequest bearer = ingest();
        bearer.addHeader("X-Device-Endpoint", "EP-LHR-GW-01");
        bearer.addHeader("Authorization", "Bearer eyJhbGciOi");
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        filter(e34, false).doFilter(bearer, res2, new MockFilterChain());
        assertThat(res2.getContentAsString()).contains("credentials_missing");

        MockHttpServletRequest hostile = ingest();
        hostile.addHeader("X-Device-Endpoint", "EP\n<script>");
        hostile.addHeader("Authorization", "DeviceToken secret-token");
        MockHttpServletResponse res3 = new MockHttpServletResponse();
        filter(e34, false).doFilter(hostile, res3, new MockFilterChain());
        assertThat(res3.getStatus()).isEqualTo(401);
        assertThat(events).hasSize(3);
        assertThat(events.get(2).endpointId()).isNull();
    }

    @Test
    void withoutEndpointSecurityEverythingIsDenied() throws Exception {
        MockHttpServletRequest req = ingest();
        req.addHeader("X-Device-Endpoint", "EP-LHR-GW-01");
        req.addHeader("Authorization", "DeviceToken secret-token");
        req.addHeader("X-Client-Cert-Sha256", THUMB);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter(DeviceEndpointAuthenticator.DENY_ALL, true).doFilter(req, res, new MockFilterChain());
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("endpoint_security_unavailable");
    }

    @Test
    void enrolmentPathsAreNotFiltered() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/device-enrolment/enrol");
        req.setRequestURI("/api/v1/device-enrolment/enrol");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter(e34, true).doFilter(req, res, chain);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(events).isEmpty();
    }

    @Test
    void thumbprintNormalisation() {
        assertThat(DeviceEndpointAuthenticationFilter.normaliseThumbprint(THUMB.toUpperCase())).isEqualTo(THUMB);
        assertThat(DeviceEndpointAuthenticationFilter.normaliseThumbprint("abc")).isNull();
        assertThat(DeviceEndpointAuthenticationFilter.normaliseThumbprint(null)).isNull();
    }
}
