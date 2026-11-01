package io.intimed.security.auth;

import io.intimed.common.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** Access to the caller: tenant, actor, roles. Tenant always comes from the authenticated identity – never from input. */
public final class Current {
    private static final ThreadLocal<IntiPrincipal> SYSTEM = new ThreadLocal<>();

    private Current() { }

    public static IntiPrincipal principal() {
        IntiPrincipal sys = SYSTEM.get();
        if (sys != null) return sys;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) throw ApiException.forbidden("unauthenticated", "Authentication required");
        if (a.getPrincipal() instanceof IntiPrincipal p) return p;
        if (a.getPrincipal() instanceof Jwt jwt) {
            Set<String> scopes = new HashSet<>(Arrays.asList(String.valueOf(jwt.getClaimAsString("scope")).split(" ")));
            return new IntiPrincipal(jwt.getSubject(), jwt.getSubject(), jwt.getClaimAsString("tenant"), "API client " + jwt.getSubject(),
                    EnumSet.noneOf(Role.class), scopes, true);
        }
        throw ApiException.forbidden("unauthenticated", "Authentication required");
    }

    public static String tenant() { return principal().tenant(); }
    public static String actor() { IntiPrincipal p = principal(); return (p.client() ? "client:" : "user:") + p.username(); }

    public static void require(Role... roles) {
        IntiPrincipal p = principal();
        if (!p.hasAny(roles)) throw ApiException.forbidden("role-required", "Requires one of " + Arrays.toString(roles));
    }

    public static void requireScope(String scope) {
        IntiPrincipal p = principal();
        if (p.client() && !p.scopes().contains(scope)) throw ApiException.forbidden("insufficient-scope", "Requires scope " + scope);
    }

    /** Runs a block as a given principal (scheduled jobs, demo scenario runner – never reachable from request input). */
    public static <T> T as(IntiPrincipal p, java.util.function.Supplier<T> body) {
        IntiPrincipal prev = SYSTEM.get();
        SYSTEM.set(p);
        try { return body.get(); } finally { if (prev == null) SYSTEM.remove(); else SYSTEM.set(prev); }
    }

    /** Runs a block as an internal system actor of a tenant (schedulers, device listeners). */
    public static <T> T asSystem(String tenant, String name, java.util.function.Supplier<T> body) {
        IntiPrincipal prev = SYSTEM.get();
        SYSTEM.set(new IntiPrincipal("system", name, tenant, name, EnumSet.noneOf(Role.class), Set.of("system"), true));
        try { return body.get(); } finally { if (prev == null) SYSTEM.remove(); else SYSTEM.set(prev); }
    }
}
