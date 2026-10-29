package com.wellnessz.security.identity;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.config.WellnessZSecurityProperties;

/** Resolves the {@link CurrentUser} from the security context. */
@Component
public class CurrentUserProvider {

    private final WellnessZSecurityProperties properties;

    public CurrentUserProvider(WellnessZSecurityProperties properties) {
        this.properties = properties;
    }

    public CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw ApiException.forbidden("Authentication required");
        }
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .collect(Collectors.toUnmodifiableSet());
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            var jwt = jwtAuth.getToken();
            String employeeId = jwt.getClaimAsString(properties.getEmployeeIdClaim());
            String username = jwt.getClaimAsString("preferred_username");
            String name = jwt.getClaimAsString("name");
            return new CurrentUser(jwt.getSubject(), username != null ? username : jwt.getSubject(),
                    employeeId != null ? employeeId : username,
                    name != null ? name : username,
                    jwt.getClaimAsString(properties.getOrgScopeClaim()), roles);
        }
        return new CurrentUser(auth.getName(), auth.getName(), auth.getName(), auth.getName(), null, roles);
    }

    /** Actor name for audit records; works for anonymous / system contexts too. */
    public String actorOrSystem() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "system";
    }
}
