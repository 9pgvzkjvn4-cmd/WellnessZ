package com.wellnessz.casekernel.device.ingest;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.config.ApiRateLimitFilter;
import com.wellnessz.security.config.WellnessZSecurityProperties;

/**
 * Security filter chain for machine traffic from devices, gateways and agents (increment 6). Ordered before the OAuth2
 * resource-server chain, it owns exactly two path families:
 * <ul>
 *   <li>{@code /api/v1/device-ingest/**} – authenticated endpoints only ({@code ROLE_DEVICE_ENDPOINT}), see
 *       {@link DeviceEndpointAuthenticationFilter}; per-endpoint rate limit with the integration budget.</li>
 *   <li>{@code /api/v1/device-enrolment/**} – reachable without a token: the E34 controller validates the one-time
 *       enrolment token, the group-PKI client certificate and the proof of possession itself and throttles attempts per source.</li>
 *   <li>{@code /api/v1/device-webhooks/**} – vendor-cloud webhooks (P6), which cannot present a client certificate: the E31
 *       controller verifies the HMAC-SHA256 signature over timestamp and raw body with the connector's webhook secret
 *       (5-minute replay window) and applies the per-connector rate limit; failures are security events.</li>
 * </ul>
 * Bearer tokens of people are not accepted here, and device credentials are not accepted anywhere else.
 */
@Configuration
public class DeviceIngestSecurityConfig {

    public static final String INGEST_PREFIX = "/api/v1/device-ingest/";
    public static final String ENROLMENT_PREFIX = "/api/v1/device-enrolment/";
    public static final String WEBHOOK_PREFIX = "/api/v1/device-webhooks/";

    @Bean
    @Order(10)
    SecurityFilterChain deviceIngestSecurityFilterChain(HttpSecurity http, DeviceSpis spis, WellnessZSecurityProperties props,
            @Value("${wellnessz.device.ingest.certificate-header:X-Client-Cert-Sha256}") String certificateHeader,
            @Value("${wellnessz.device.ingest.require-client-certificate:true}") boolean requireClientCertificate) throws Exception {
        DeviceEndpointAuthenticationFilter endpointFilter =
                new DeviceEndpointAuthenticationFilter(spis::authenticator, spis::securityEvents, certificateHeader, requireClientCertificate, Clock.systemUTC());
        http
            .securityMatcher(INGEST_PREFIX + "**", ENROLMENT_PREFIX + "**", WEBHOOK_PREFIX + "**")
            .csrf(csrf -> csrf.disable()) // machine clients without ambient credentials: CSRF not applicable
            .cors(cors -> cors.disable()) // never called from browsers
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(ENROLMENT_PREFIX + "**", WEBHOOK_PREFIX + "**").permitAll()
                .anyRequest().hasRole(Roles.DEVICE_ENDPOINT))
            .addFilterBefore(endpointFilter, AnonymousAuthenticationFilter.class)
            .addFilterAfter(new ApiRateLimitFilter(props, Clock.systemUTC()), DeviceEndpointAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED, "endpoint_unauthenticated"))
                .accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN, "endpoint_forbidden")))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    private static void problem(jakarta.servlet.http.HttpServletResponse res, HttpStatus status, String code) throws java.io.IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":" + status.value()
                + ",\"code\":\"" + code + "\"}");
    }
}
