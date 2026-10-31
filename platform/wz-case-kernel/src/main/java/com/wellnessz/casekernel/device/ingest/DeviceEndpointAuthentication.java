package com.wellnessz.casekernel.device.ingest;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.common.model.Roles;

/** Authentication of a device / gateway endpoint: principal {@link DeviceEndpointPrincipal}, authority {@code ROLE_DEVICE_ENDPOINT}. */
public final class DeviceEndpointAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient DeviceEndpointPrincipal principal;

    public DeviceEndpointAuthentication(DeviceEndpointPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + Roles.DEVICE_ENDPOINT),
                new SimpleGrantedAuthority("ENDPOINT_KIND_" + principal.kind().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return ""; // the token is never kept after authentication
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return principal.authenticationName();
    }
}
