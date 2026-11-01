package com.wellnessz.e31.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * "Poll now" runs in the administrator's request thread, but E34 resolves connector secrets for the engine only (it refuses a
 * person's authentication): the secret resolution runs in an empty security context and the person's context comes back after.
 */
class CloudPollEngineContextTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void theSecretResolutionSeesNoPersonAndThePersonsContextIsRestored() {
        Authentication admin = new UsernamePasswordAuthenticationToken("deviceadmin1", "n/a", List.of(new SimpleGrantedAuthority("ROLE_DEVICE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);
        AtomicReference<Authentication> seen = new AtomicReference<>(admin);
        String value = CloudPollService.asEngine(() -> {
            seen.set(SecurityContextHolder.getContext().getAuthentication());
            return "resolved";
        });
        assertThat(value).isEqualTo("resolved");
        assertThat(seen.get()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(admin);
    }

    @Test
    void thePersonsContextIsRestoredWhenTheResolutionFails() {
        Authentication admin = new UsernamePasswordAuthenticationToken("deviceadmin1", "n/a", List.of());
        SecurityContextHolder.getContext().setAuthentication(admin);
        assertThatThrownBy(() -> CloudPollService.asEngine(() -> {
            throw new IllegalStateException("vault unavailable");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(admin);
    }
}
