package com.wellnessz.bff;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BffSecurityTest {

    private static PostgreSQLContainer container;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("WZ_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            String user = System.getenv().getOrDefault("WZ_TEST_DB_USERNAME", "wz_app");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> user);
            registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("WZ_TEST_DB_PASSWORD", ""));
            registry.add("spring.flyway.user", () -> System.getenv().getOrDefault("WZ_TEST_DB_MIGRATOR_USERNAME", user));
            registry.add("spring.flyway.password", () -> System.getenv().getOrDefault("WZ_TEST_DB_MIGRATOR_PASSWORD", ""));
            registry.add("spring.flyway.placeholders.app_role", () -> user);
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.flyway.user", container::getUsername);
        registry.add("spring.flyway.password", container::getPassword);
        registry.add("spring.flyway.placeholders.app_role", container::getUsername);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void anonymousSessionProbe() throws Exception {
        mvc.perform(get("/bff/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void apiRequiresSessionAndReturns401NotRedirect() throws Exception {
        mvc.perform(get("/bff/api/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedSessionExposesRolesOnly() throws Exception {
        mvc.perform(get("/bff/session").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.roles[0]").value("EMPLOYEE"))
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    @Test
    void workspaceIsRoleGated() throws Exception {
        mvc.perform(get("/bff/api/workspace/cases").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void stateChangingCallsRequireCsrfToken() throws Exception {
        mvc.perform(post("/bff/api/consents").with(oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(oidcLogin()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().exists("Location"));
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }
}
