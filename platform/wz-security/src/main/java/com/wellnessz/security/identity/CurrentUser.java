package com.wellnessz.security.identity;

import java.util.Set;

/**
 * The authenticated principal as seen by business logic.
 *
 * @param subject     stable IdP subject identifier
 * @param username    login name (for display / audit)
 * @param employeeId  HR employee number linking to the employee master, may be {@code null} for machine clients
 * @param displayName human readable name
 * @param orgScope    org-unit path the user belongs to or manages, e.g. {@code /AIRLINE/OPS/CABIN}
 * @param roles       application roles without {@code ROLE_} prefix
 */
public record CurrentUser(String subject, String username, String employeeId, String displayName, String orgScope,
                          Set<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean hasAnyRole(String... candidates) {
        for (String c : candidates) {
            if (roles.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * External partner organisation of the caller – partner portal users and partner / insurer machine clients carry
     * {@code org_scope = /PARTNERS/<CODE>} (a claim mapper on their own IdP client). {@code null} for internal users and
     * for machine clients that are not bound to an organisation.
     */
    public String partnerOrganisation() {
        if (orgScope == null || !orgScope.startsWith("/PARTNERS/")) {
            return null;
        }
        String code = orgScope.substring("/PARTNERS/".length());
        return code.matches("[A-Z0-9_]{2,40}") ? code : null;
    }
}
