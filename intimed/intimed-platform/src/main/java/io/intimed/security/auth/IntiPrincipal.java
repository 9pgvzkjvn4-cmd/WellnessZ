package io.intimed.security.auth;

import java.io.Serializable;
import java.util.Set;

/** Authenticated user (console session) or API client (OAuth2 client credentials). */
public record IntiPrincipal(String id, String username, String tenant, String displayName, Set<Role> roles,
                            Set<String> scopes, boolean client) implements Serializable {
    public boolean has(Role r) { return roles.contains(r); }
    public boolean hasAny(Role... rs) { for (Role r : rs) if (roles.contains(r)) return true; return false; }
}
