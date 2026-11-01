package io.intimed.web;

import io.intimed.common.ApiException;
import io.intimed.security.RateLimiter;
import io.intimed.security.audit.AuditService;
import io.intimed.security.auth.Current;
import io.intimed.security.auth.IntiPrincipal;
import io.intimed.security.auth.Role;
import io.intimed.security.auth.Totp;
import io.intimed.security.auth.UserService;
import io.intimed.tenancy.TenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Console login: password → (TOTP for privileged roles) → session. Rate-limited per client address. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String PENDING = "intimed.pendingMfaUser";
    private final UserService users;
    private final AuditService audit;
    private final SecurityContextRepository repo;
    private final RateLimiter limiter;
    private final TenantService tenants;
    private final boolean demo;

    public AuthController(UserService users, AuditService audit, SecurityContextRepository repo, RateLimiter limiter,
                          TenantService tenants, @Value("${intimed.demo.enabled:false}") boolean demo) {
        this.users = users;
        this.audit = audit;
        this.repo = repo;
        this.limiter = limiter;
        this.tenants = tenants;
        this.demo = demo;
    }

    public record Login(String username, String password) { }
    public record Mfa(String code) { }

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("header", token.getHeaderName(), "token", token.getToken()); }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Login body, HttpServletRequest req, HttpServletResponse res) {
        if (!limiter.allow("login:" + req.getRemoteAddr(), 0.5, 10)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many login attempts");
        }
        UserService.User u = users.authenticate(body.username(), body.password());
        tenants.require(u.tenant());
        HttpSession s = req.getSession(true);
        req.changeSessionId();
        if (u.mfaRequired()) {
            s.setAttribute(PENDING, u.username());
            return Map.of("mfaRequired", true, "demoAuthenticator", demo);
        }
        establish(u, req, res);
        return Map.of("mfaRequired", false, "user", me(u.principal()));
    }

    @PostMapping("/mfa")
    public Map<String, Object> mfa(@RequestBody Mfa body, HttpServletRequest req, HttpServletResponse res) {
        HttpSession s = req.getSession(false);
        String name = s == null ? null : (String) s.getAttribute(PENDING);
        if (name == null) throw ApiException.forbidden("no-pending-login", "Sign in with your password first");
        if (!limiter.allow("mfa:" + name, 0.2, 5)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many codes");
        UserService.User u = users.find(name);
        if (!users.verifyTotp(u, body.code())) {
            audit.record(u.tenant(), "user:" + name, "auth.mfa.failure", "user/" + name, null);
            throw ApiException.forbidden("bad-mfa", "Invalid authentication code");
        }
        s.removeAttribute(PENDING);
        req.changeSessionId();
        establish(u, req, res);
        return Map.of("user", me(u.principal()));
    }

    /** Demo authenticator (demo profile only): shows the current TOTP of the user whose password was just verified. */
    @GetMapping("/demo-mfa")
    public Map<String, Object> demoMfa(HttpServletRequest req) {
        if (!demo) throw ApiException.notFound("resource");
        HttpSession s = req.getSession(false);
        String name = s == null ? null : (String) s.getAttribute(PENDING);
        if (name == null) throw ApiException.forbidden("no-pending-login", "No pending login");
        return Map.of("code", Totp.now(users.totpSecret(users.find(name))), "label", "DEMO AUTHENTICATOR – demo profile only");
    }

    @GetMapping("/me")
    public Map<String, Object> me() { return me(Current.principal()); }

    private Map<String, Object> me(IntiPrincipal p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", p.username());
        m.put("displayName", p.displayName());
        m.put("tenant", p.tenant());
        m.put("tenantName", tenants.require(p.tenant()).name());
        m.put("roles", p.roles());
        m.put("roleLabels", p.roles().stream().map(Role::label).toList());
        m.put("environment", "LOCAL");
        return m;
    }

    private void establish(UserService.User u, HttpServletRequest req, HttpServletResponse res) {
        IntiPrincipal p = u.principal();
        var auth = new UsernamePasswordAuthenticationToken(p, null,
                p.roles().stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r.name())).toList());
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(auth);
        SecurityContextHolder.setContext(ctx);
        repo.saveContext(ctx, req, res);
        audit.record(u.tenant(), "user:" + u.username(), "auth.login", "user/" + u.username(), Map.of("mfa", u.mfaRequired()));
    }
}
