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
        Set<String> roles = RoleClaims.extract(jwt.getClaims(), properties.getRoleClaims(), properties.getGroupRoleMapping());
        Collection<GrantedAuthority> authorities = roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .collect(Collectors.toUnmodifiableSet());
        String name = jwt.getClaimAsString("preferred_username");
        return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
    }
}
