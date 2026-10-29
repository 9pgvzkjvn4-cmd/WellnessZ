package com.wellnessz.bff;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets the SPA discover whether a session exists and which roles it carries (for UI gating only). */
@RestController
public class SessionController {

    @GetMapping("/bff/session")
    public Map<String, Object> session(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof OidcUser user)) {
            return Map.of("authenticated", false);
        }
        List<String> roles = authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_")).map(a -> a.substring(5)).sorted().toList();
        String name = user.getFullName() != null ? user.getFullName() : user.getPreferredUsername();
        String username = user.getPreferredUsername() == null ? user.getSubject() : user.getPreferredUsername();
        return Map.of("authenticated", true, "name", name == null ? user.getSubject() : name, "username", username, "roles", roles);
    }
}
