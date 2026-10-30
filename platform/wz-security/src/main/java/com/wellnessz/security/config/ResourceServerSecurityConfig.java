package com.wellnessz.security.config;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.wellnessz.security.identity.WellnessZJwtConverter;

/**
 * OAuth2 resource-server configuration for the core API.
 * <ul>
 *   <li>Multi-issuer: tokens from any configured trusted issuer (Entra ID, Keycloak, Okta…) are accepted; JWKS
 *       are discovered lazily per issuer, untrusted issuers are rejected before any network call.</li>
 *   <li>Audience, expiry, not-before and issuer are validated; algorithms are restricted by the JWKS.</li>
 *   <li>Stateless, no sessions, no CSRF (bearer tokens only), strict security headers.</li>
 *   <li>Method security ({@code @PreAuthorize}) enabled for fine-grained RBAC; ABAC via {@code PolicyEngine}.</li>
 *   <li>Per-principal rate limit ({@link ApiRateLimitFilter}) behind the edge WAF.</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties(WellnessZSecurityProperties.class)
public class ResourceServerSecurityConfig {

    /** Roles held by people outside the airline group (external portals, job candidates). */
    static final Set<String> EXTERNAL_ROLES = Set.of("ROLE_PARTNER_PROVIDER", "ROLE_TPA_ADJUSTER", "ROLE_CANDIDATE");

    static final String[] INTERNAL_REFERENCE_DATA = {"/api/v1/masterdata/org-units", "/api/v1/masterdata/job-codes",
        "/api/v1/masterdata/hazards", "/api/v1/masterdata/fitness-standards", "/api/v1/masterdata/restrictions"};

    /** Grants access when the principal holds at least one role that is not an external role. */
    static final AuthorizationManager<RequestAuthorizationContext> INTERNAL_PRINCIPAL = (authentication, context) -> {
        var auth = authentication.get();
        boolean internal = auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .anyMatch(a -> a.startsWith("ROLE_") && !EXTERNAL_ROLES.contains(a));
        return new AuthorizationDecision(internal);
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, WellnessZSecurityProperties props,
                                               JwtIssuerAuthenticationManagerResolver issuerResolver) throws Exception {
        http
            .securityMatcher("/api/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error")
            .csrf(csrf -> csrf.disable()) // stateless bearer-token API: no ambient credentials, CSRF not applicable
            .cors(cors -> cors.configurationSource(corsConfigurationSource(props)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                .requestMatchers("/api/v1/integration/**").hasAnyRole("INTEGRATION", "PLATFORM_ADMIN", "OH_ADMIN")
                .requestMatchers("/api/v1/audit/**").hasAnyRole("AUDITOR", "PLATFORM_ADMIN")
                // organisation structure and internal reference data: not for external partner / TPA / candidate accounts
                .requestMatchers(HttpMethod.GET, INTERNAL_REFERENCE_DATA).access(INTERNAL_PRINCIPAL)
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.authenticationManagerResolver(issuerResolver))
            .addFilterAfter(new ApiRateLimitFilter(props, Clock.systemUTC()), BearerTokenAuthenticationFilter.class)
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    @Bean
    JwtIssuerAuthenticationManagerResolver issuerAuthenticationManagerResolver(WellnessZSecurityProperties props) {
        List<String> trusted = List.copyOf(props.getIssuers());
        Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();
        WellnessZJwtConverter converter = new WellnessZJwtConverter(props);
        return new JwtIssuerAuthenticationManagerResolver(issuer -> {
            if (issuer == null || !trusted.contains(issuer)) {
                return null; // untrusted issuer -> 401, no outbound discovery call
            }
            return managers.computeIfAbsent(issuer, iss -> {
                NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(iss).build();
                decoder.setJwtValidator(tokenValidator(iss, props.getAudience()));
                JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
                provider.setJwtAuthenticationConverter(converter);
                return provider::authenticate;
            });
        });
    }

    static OAuth2TokenValidator<Jwt> tokenValidator(String issuer, String audience) {
        OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
                aud -> aud != null && aud.contains(audience));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator);
    }

    private static CorsConfigurationSource corsConfigurationSource(WellnessZSecurityProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.getCorsOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Correlation-Id"));
        config.setExposedHeaders(List.of("X-Correlation-Id"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
