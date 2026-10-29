package com.wellnessz.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RoleClaimsTest {

    @Test
    void extractsKeycloakRealmRoles() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("employee", "offline_access", "ROLE_manager")));
        assertThat(RoleClaims.extract(claims, List.of("realm_access.roles"), Map.of()))
                .contains("EMPLOYEE", "MANAGER", "OFFLINE_ACCESS");
    }

    @Test
    void mapsEntraGroupsAndAppRoles() {
        Map<String, Object> claims = Map.of("roles", List.of("OH.Clinician"), "groups", List.of("8f1d-guid"));
        var roles = RoleClaims.extract(claims, List.of("roles", "groups"), Map.of("8f1d-guid", "HR", "OH.Clinician", "OH_CLINICIAN"));
        assertThat(roles).containsExactlyInAnyOrder("OH_CLINICIAN", "HR");
    }

    @Test
    void rejectsMalformedRoleValues() {
        Map<String, Object> claims = Map.of("roles", List.of("x' or 1=1", "", "9LIVES"));
        assertThat(RoleClaims.extract(claims, List.of("roles"), Map.of())).isEmpty();
    }
}
