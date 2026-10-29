package com.wellnessz.app;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real PostgreSQL 16. Uses {@code WZ_TEST_DB_URL} (+ _USERNAME/_PASSWORD, and optional
 * _MIGRATOR_USERNAME/_MIGRATOR_PASSWORD) when provided – e.g. the CI service container – otherwise starts a
 * Testcontainers instance (requires Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    private static PostgreSQLContainer container;

    /** Key material is generated per test run – no keys are committed to the repository. */
    static final String MASTER_KEY = randomKey(32);
    static final String PSEUDONYM_KEY = randomKey(32);

    static String randomKey(int bytes) {
        byte[] k = new byte[bytes];
        new java.security.SecureRandom().nextBytes(k);
        return java.util.Base64.getEncoder().encodeToString(k);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("WZ_MASTER_KEY", () -> MASTER_KEY);
        registry.add("WZ_PSEUDONYM_KEY", () -> PSEUDONYM_KEY);
        String url = System.getenv("WZ_TEST_DB_URL");
        if (url != null && !url.isBlank()) {
            String user = env("WZ_TEST_DB_USERNAME", "wz_app");
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> user);
            registry.add("spring.datasource.password", () -> env("WZ_TEST_DB_PASSWORD", ""));
            registry.add("spring.flyway.user", () -> env("WZ_TEST_DB_MIGRATOR_USERNAME", user));
            registry.add("spring.flyway.password", () -> env("WZ_TEST_DB_MIGRATOR_PASSWORD", env("WZ_TEST_DB_PASSWORD", "")));
            registry.add("spring.flyway.placeholders.app_role", () -> user);
            return;
        }
        synchronized (IntegrationTestBase.class) {
            if (container == null) {
                container = new PostgreSQLContainer("postgres:16-alpine");
                container.start();
            }
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
        registry.add("spring.flyway.user", container::getUsername);
        registry.add("spring.flyway.password", container::getPassword);
        registry.add("spring.flyway.placeholders.app_role", container::getUsername);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
