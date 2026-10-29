package com.wellnessz.common.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts application roles from identity-provider token claims, independent of the IdP vendor.
 * <ul>
 *   <li>Keycloak: {@code realm_access.roles}, {@code resource_access.<client>.roles}</li>
 *   <li>Microsoft Entra ID: {@code roles} (app roles) and {@code groups} (object ids, mapped via a lookup table)</li>
 *   <li>Generic OIDC (Okta, Ping, Google): any configured claim path</li>
 * </ul>
 */
public final class RoleClaims {

    private RoleClaims() {
    }

    /**
     * @param claims       token claims
     * @param claimPaths   dot-separated claim paths to inspect, e.g. {@code realm_access.roles}
     * @param groupMapping optional mapping of IdP group ids/names to application roles
     * @return normalised upper-case role names (without {@code ROLE_} prefix)
     */
    public static Set<String> extract(Map<String, Object> claims, List<String> claimPaths, Map<String, String> groupMapping) {
        Set<String> roles = new LinkedHashSet<>();
        for (String path : claimPaths) {
            Object value = resolve(claims, path);
            if (value instanceof Collection<?> values) {
                for (Object v : values) {
                    addRole(roles, String.valueOf(v), groupMapping);
                }
            } else if (value instanceof String s) {
                for (String part : s.split("[ ,]")) {
                    addRole(roles, part, groupMapping);
                }
            }
        }
        return roles;
    }

    private static void addRole(Set<String> roles, String raw, Map<String, String> groupMapping) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        String mapped = groupMapping != null ? groupMapping.getOrDefault(raw, raw) : raw;
        String normalised = mapped.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if (normalised.startsWith("ROLE_")) {
            normalised = normalised.substring(5);
        }
        // only accept well-formed role names; defends against claim injection of odd values
        if (normalised.matches("[A-Z][A-Z0-9_]{0,63}")) {
            roles.add(normalised);
        }
    }

    @SuppressWarnings("unchecked")
    static Object resolve(Map<String, Object> claims, String path) {
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(segment);
            } else {
                return null;
            }
        }
        return current;
    }
}
