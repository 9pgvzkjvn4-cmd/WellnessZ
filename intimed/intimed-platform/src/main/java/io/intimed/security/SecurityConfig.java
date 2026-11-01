package io.intimed.security;

import io.intimed.security.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Three filter chains (SC-11):
 * <ol>
 *   <li>Machine APIs ({@code /fhir/**}, {@code /api/v1/**}): stateless OAuth2 bearer (ES256 JWT, scopes, tenant claim).</li>
 *   <li>Public machine endpoints ({@code /oauth2/token}, {@code /api/enrol}, {@code /api/webhooks/**}): no session, no
 *       CSRF – each authenticates itself (client secret, one-time enrolment token + CSR, HMAC signature).</li>
 *   <li>Console (everything else): session cookie (Secure, HttpOnly, SameSite=Strict), CSRF double-submit token,
 *       MFA for privileged roles enforced in {@code AuthController}.</li>
 * </ol>
 * All responses carry a strict CSP (no inline script), HSTS, no-referrer and a restrictive Permissions-Policy.
 */
@Configuration
public class SecurityConfig {
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
            + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'";

    @Bean
    public SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    private static void headers(HttpSecurity http) throws Exception {
        http.headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(CSP))
                .httpStrictTransportSecurity(s -> s.includeSubDomains(true).maxAgeInSeconds(31536000))
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .frameOptions(f -> f.sameOrigin())
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", "camera=(self), microphone=(), geolocation=(), usb=(self), bluetooth=(self), serial=(self)"))
                .addHeaderWriter(new StaticHeadersWriter("Cross-Origin-Opener-Policy", "same-origin"))
                .addHeaderWriter(new StaticHeadersWriter("Cache-Control", "no-store")));
    }

    @Bean
    @Order(1)
    SecurityFilterChain machineApi(HttpSecurity http, TokenService tokens) throws Exception {
        http.securityMatcher("/fhir/**", "/api/v1/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/fhir/r4/metadata").permitAll().anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(tokens.decoder())));
        headers(http);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicMachine(HttpSecurity http) throws Exception {
        http.securityMatcher("/oauth2/**", "/api/enrol", "/api/webhooks/**", "/.well-known/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        headers(http);
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain console(HttpSecurity http, SecurityContextRepository repo) throws Exception {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.secure(true).sameSite("Strict").path("/"));
        http.csrf(c -> c.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .securityContext(s -> s.securityContextRepository(repo))
                .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()).maximumSessions(3))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/", "/index.html", "/login.html", "/assets/**", "/favicon.svg", "/host-demo/**",
                                "/api/auth/login", "/api/auth/mfa", "/api/auth/csrf", "/api/auth/demo-mfa", "/actuator/health",
                                "/actuator/health/**", "/api/public/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.logoutUrl("/api/auth/logout").logoutSuccessHandler((q, r, a) -> r.setStatus(204)).deleteCookies("JSESSIONID"));
        headers(http);
        return http.build();
    }

    /** Materialises the deferred CSRF token so the SPA always receives the XSRF-TOKEN cookie. */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
            CsrfToken t = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            if (t != null) t.getToken();
            chain.doFilter(req, res);
        }
    }
}
