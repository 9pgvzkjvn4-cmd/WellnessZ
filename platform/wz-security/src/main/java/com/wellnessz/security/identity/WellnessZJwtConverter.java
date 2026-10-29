package com.wellnessz.security.identity;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.wellnessz.common.security.RoleClaims;
import com.wellnessz.security.config.WellnessZSecurityProperties;

/** Maps IdP-specific JWT claims to {@code ROLE_*} authorities, whichever OIDC provider issued the token. */
public class WellnessZJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final WellnessZSecurityProperties properties;

    public WellnessZJwtConverter(WellnessZSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<String> roles = withoutUnprovenMfaRoles(jwt,
                RoleClaims.extract(jwt.getClaims(), properties.getRoleClaims(), properties.getGroupRoleMapping()));
        Collection<GrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toUnmodifiableSet());
        String name = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }

    /**
     * External roles (partner providers, TPA adjusters) are granted only when the token proves multi-factor
     * authentication through {@code amr} or {@code acr}; without that proof the role is dropped and the portal is denied.
     */
    Set<String> withoutUnprovenMfaRoles(Jwt jwt, Set<String> roles) {
        if (properties.getMfaRequiredRoles().stream().noneMatch(roles::contains) || hasMfa(jwt)) {
            return roles;
        }
        return roles.stream().filter(r -> !properties.getMfaRequiredRoles().contains(r)).collect(Collectors.toUnmodifiableSet());
    }

    private boolean hasMfa(Jwt jwt) {
        Object amr = jwt.getClaims().get("amr");
        if (amr instanceof Collection<?> values && values.stream().map(String::valueOf).anyMatch(properties.getMfaAmrValues()::contains)) {
            return true;
        }
        String acr = jwt.getClaimAsString("acr");
        return acr != null && properties.getMfaAcrValues().contains(acr);
    }
}
