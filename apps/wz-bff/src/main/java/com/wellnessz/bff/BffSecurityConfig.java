package com.wellnessz.bff;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.wellnessz.common.security.RoleClaims;

import tools.jackson.databind.json.JsonMapper;

/** Session-based security for the BFF: OIDC login, CSRF protection, strict headers, role-gated workspace API. */
@Configuration
public class BffSecurityConfig {

    static final List<String> WORKSPACE_ROLES = List.of("MANAGER", "HR", "OH_CLINICIAN", "OH_ADMIN", "CASE_MANAGER",
            "PSYCH_CLINICIAN", "PLATFORM_ADMIN");

    @Bean
    SecurityFilterChain bffSecurityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations,
                                               OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService,
                                               @Value("${wellnessz.bff.post-logout-redirect-uri:{baseUrl}/}") String postLogout)
            throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/bff/session", "/error").permitAll()
                .requestMatchers("/bff/api/workspace/**").hasAnyRole(WORKSPACE_ROLES.toArray(String[]::new))
                .requestMatchers("/bff/api/**").authenticated()
                .anyRequest().denyAll())
            .oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(oidcUserService)))
            // XHR callers get 401 instead of a redirect to the IdP
            .exceptionHandling(e -> e.defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    PathPatternRequestMatcher.pathPattern("/bff/**")))
            .csrf(csrf -> csrf.spa())
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(new SpaOidcLogoutHandler(registrations, postLogout))
                    .deleteCookies("WZSESSION"))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()).maximumSessions(3))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
        return http.build();
    }

    /**
     * Maps roles from the ID token and from the access token (Keycloak puts realm roles in the access token,
     * Entra ID app roles appear in both) onto {@code ROLE_*} authorities of the session principal.
     */
    /** Entra ID security-group object ids → application roles, mirroring the core API's group-role-mapping. */
    @Bean
    @org.springframework.boot.context.properties.ConfigurationProperties(prefix = "wellnessz.bff.group-role-mapping")
    Map<String, String> groupRoleMapping() {
        return new HashMap<>();
    }

    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService(Map<String, String> groupRoleMapping) {
        OidcUserService delegate = new OidcUserService();
        JsonMapper json = JsonMapper.builder().build();
        List<String> claimPaths = List.of("realm_access.roles", "roles", "groups");
        return request -> {
            OidcUser user = java.util.Objects.requireNonNull(delegate.loadUser(request), "OIDC user");
            Map<String, Object> claims = new HashMap<>(user.getClaims());
            Set<String> roles = new HashSet<>(RoleClaims.extract(claims, claimPaths, groupRoleMapping));
            String token = request.getAccessToken().getTokenValue();
            String[] parts = token.split("\\.");
            if (parts.length == 3) {
                // received directly from the token endpoint over TLS, so the payload can be read without re-verification
                @SuppressWarnings("unchecked")
                Map<String, Object> at = json.readValue(java.util.Base64.getUrlDecoder().decode(parts[1]), Map.class);
                if (at != null) {
                    roles.addAll(RoleClaims.extract(at, claimPaths, groupRoleMapping));
                }
            }
            Collection<GrantedAuthority> authorities = new HashSet<>(user.getAuthorities());
            roles.forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
            return new DefaultOidcUser(authorities, user.getIdToken(), user.getUserInfo(), "preferred_username");
        };
    }

    /** RP-initiated logout for SPAs: returns 200 + Location (the IdP end-session URL) instead of a 302. */
    static class SpaOidcLogoutHandler extends OidcClientInitiatedLogoutSuccessHandler {

        private final ClientRegistrationRepository registrations;
        private final JsonMapper json = JsonMapper.builder().build();

        SpaOidcLogoutHandler(ClientRegistrationRepository registrations, String postLogout) {
            super(registrations);
            this.registrations = registrations;
            setPostLogoutRedirectUri(postLogout);
        }

        @Override
        public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
                throws IOException {
            String target = safeTarget(determineTargetUrl(request, response, authentication));
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json");
            response.setHeader("Location", target);
            json.writeValue(response.getOutputStream(), Map.of("logoutUrl", target));
        }

        /** Only the IdP's own end-session endpoint (from trusted OIDC metadata) or a relative path is allowed. */
        String safeTarget(String target) {
            if (target == null || target.startsWith("/") && !target.startsWith("//")) {
                return target == null ? "/" : target;
            }
            ClientRegistration reg = registrations.findByRegistrationId("wellnessz");
            Object endSession = reg == null ? null
                    : reg.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint");
            return endSession != null && target.startsWith(endSession + "?") ? target : "/";
        }
    }
}
