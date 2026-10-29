package com.wellnessz.security.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import com.wellnessz.security.config.WellnessZSecurityProperties;

class MfaRoleGateTest {

    private final WellnessZJwtConverter converter = new WellnessZJwtConverter(new WellnessZSecurityProperties());

    private static Jwt token(Map<String, Object> extra) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").subject("u1").claim("preferred_username", "partner1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .claim("realm_access", Map.of("roles", List.of("PARTNER_PROVIDER", "EMPLOYEE")));
        extra.forEach(b::claim);
        return b.build();
    }

    private List<String> roles(Jwt jwt) {
        return converter.convert(jwt).getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList();
    }

    @Test
    void partnerRoleIsDroppedWithoutMfaProof() {
        assertThat(roles(token(Map.of()))).containsExactly("ROLE_EMPLOYEE");
        assertThat(roles(token(Map.of("amr", List.of("pwd"))))).containsExactly("ROLE_EMPLOYEE");
        assertThat(roles(token(Map.of("acr", "1")))).containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    void partnerRoleIsGrantedWithAmrOrAcrProof() {
        assertThat(roles(token(Map.of("amr", List.of("pwd", "otp"))))).containsExactly("ROLE_EMPLOYEE", "ROLE_PARTNER_PROVIDER");
        assertThat(roles(token(Map.of("acr", "2")))).containsExactly("ROLE_EMPLOYEE", "ROLE_PARTNER_PROVIDER");
    }

    @Test
    void gateCanBeSwitchedOffPerEnvironment() {
        WellnessZSecurityProperties p = new WellnessZSecurityProperties();
        p.setMfaRequiredRoles(List.of());
        assertThat(new WellnessZJwtConverter(p).convert(token(Map.of())).getAuthorities()).hasSize(2);
    }
}
