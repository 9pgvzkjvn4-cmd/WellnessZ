package io.intimed.security.auth;

import io.intimed.common.ApiException;
import io.intimed.common.Hashing;
import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.TenantKeyService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Console users: Argon2id password hashes, lockout after 5 failures (15 min), TOTP secrets encrypted with the tenant DEK,
 * MFA mandatory for privileged roles. In DEV/UAT/PROD identity is federated through Keycloak/Entra/Google (OIDC); this
 * local store is the MacBook kit's IdP and keeps the same role model.
 */
@Service
public class UserService {
    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK = Duration.ofMinutes(15);
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TenantKeyService keys;
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public UserService(JdbcClient jdbc, AuditService audit, TenantKeyService keys) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.keys = keys;
    }

    public record User(String id, String tenant, String username, String hash, String displayName, Set<Role> roles,
                       String totpSecretEnc, int failures, Instant lockedUntil) {
        public boolean mfaRequired() { return roles.stream().anyMatch(Role::mfaRequired); }
        public IntiPrincipal principal() { return new IntiPrincipal(id, username, tenant, displayName, roles, Set.of(), false); }
    }

    public static void validatePassword(String pw) {
        if (pw == null || pw.length() < 12 || !pw.matches(".*[A-Z].*") || !pw.matches(".*[a-z].*") || !pw.matches(".*\\d.*")) {
            throw ApiException.bad("weak-password", "password must have ≥ 12 characters with upper, lower case and digits");
        }
    }

    public User create(String tenant, String username, String password, String displayName, Set<Role> roles, String actor) {
        validatePassword(password);
        if (!username.matches("[a-z0-9._-]{3,60}")) throw ApiException.bad("invalid-username", "invalid username");
        String id = Hashing.id("usr");
        String secret = roles.stream().anyMatch(Role::mfaRequired) ? keys.encrypt(tenant, "totp/" + username, Totp.newSecret()) : null;
        jdbc.sql("insert into users(id, tenant_id, username, password_hash, display_name, roles, totp_secret, created_at) values (:i,:t,:u,:h,:d,:r,:s,:c)")
                .param("i", id).param("t", tenant).param("u", username).param("h", encoder.encode(password)).param("d", displayName)
                .param("r", roles.stream().map(Enum::name).sorted().collect(Collectors.joining(","))).param("s", secret)
                .param("c", java.sql.Timestamp.from(Instant.now())).update();
        audit.record(tenant, actor, "user.create", "user/" + username, Map.of("roles", roles.toString()));
        return find(username);
    }

    public User find(String username) {
        return jdbc.sql("select id, tenant_id, username, password_hash, display_name, roles, totp_secret, failed_attempts, locked_until from users where username = :u")
                .param("u", username).query((rs, i) -> new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        Arrays.stream(rs.getString(6).split(",")).map(Role::valueOf).collect(Collectors.toCollection(() -> EnumSet.noneOf(Role.class))),
                        rs.getString(7), rs.getInt(8), rs.getTimestamp(9) == null ? null : rs.getTimestamp(9).toInstant())).optional().orElse(null);
    }

    public List<Map<String, Object>> list(String tenant) {
        return jdbc.sql("select username, display_name, roles, locked_until from users where tenant_id = :t order by username").param("t", tenant)
                .query((rs, i) -> Map.<String, Object>of("username", rs.getString(1), "displayName", rs.getString(2),
                        "roles", List.of(rs.getString(3).split(",")), "locked", rs.getTimestamp(4) != null && rs.getTimestamp(4).toInstant().isAfter(Instant.now()))).list();
    }

    /** Verifies the password with lockout; the same generic error for unknown users and wrong passwords. */
    public User authenticate(String username, String password) {
        User u = username == null ? null : find(username.toLowerCase());
        if (u == null) {
            encoder.matches(password == null ? "" : password, "$argon2id$v=19$m=16384,t=2,p=1$c29tZXNhbHRzb21lc2FsdA$Q7m5w0m3tVJ7n6w3J1o1oA");
            throw ApiException.forbidden("bad-credentials", "Invalid username or password");
        }
        if (u.lockedUntil() != null && u.lockedUntil().isAfter(Instant.now())) {
            audit.record(u.tenant(), "user:" + u.username(), "auth.locked", "user/" + u.username(), null);
            throw ApiException.forbidden("account-locked", "Account temporarily locked after repeated failures");
        }
        if (!encoder.matches(password == null ? "" : password, u.hash())) {
            int f = u.failures() + 1;
            jdbc.sql("update users set failed_attempts = :f, locked_until = :l where id = :i").param("f", f)
                    .param("l", f >= MAX_FAILURES ? java.sql.Timestamp.from(Instant.now().plus(LOCK)) : null).param("i", u.id()).update();
            audit.record(u.tenant(), "user:" + u.username(), "auth.failure", "user/" + u.username(), Map.of("failures", f));
            throw ApiException.forbidden("bad-credentials", "Invalid username or password");
        }
        jdbc.sql("update users set failed_attempts = 0, locked_until = null where id = :i").param("i", u.id()).update();
        return u;
    }

    public String totpSecret(User u) {
        return u.totpSecretEnc() == null ? null : keys.decrypt(u.tenant(), "totp/" + u.username(), u.totpSecretEnc());
    }

    public boolean verifyTotp(User u, String code) {
        String s = totpSecret(u);
        return s != null && Totp.verify(s, code);
    }
}
