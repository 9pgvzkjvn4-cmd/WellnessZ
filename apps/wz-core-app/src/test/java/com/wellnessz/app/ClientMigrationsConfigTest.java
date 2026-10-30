package com.wellnessz.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ClientMigrationsConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ClientMigrationsConfig.class));

    @Test
    void inactiveWithoutLocations() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(FlywayMigrationStrategy.class));
        runner.withPropertyValues("wellnessz.flyway.client.locations=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FlywayMigrationStrategy.class));
        runner.withPropertyValues("wellnessz.flyway.client.locations=  ")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(FlywayMigrationStrategy.class));
    }

    @Test
    void activeWithLocations() {
        runner.withPropertyValues("wellnessz.flyway.client.locations=filesystem:/opt/wellnessz/client-migrations")
                .run(ctx -> assertThat(ctx).hasSingleBean(FlywayMigrationStrategy.class));
    }

    @Test
    void clientPassKeepsConnectionButUsesOwnSchemaAndHistory() {
        Configuration base = Flyway.configure()
                .dataSource("jdbc:postgresql://db.invalid:5432/wellnessz", "wz_migrator", "secret")
                .locations("classpath:db/migration")
                .schemas("platform", "masterdata")
                .defaultSchema("platform")
                .placeholders(Map.of("app_role", "wz_app"))
                .load()
                .getConfiguration();

        Configuration client = ClientMigrationsConfig.clientFlyway(base,
                List.of("filesystem:/opt/wellnessz/client-migrations"), List.of("client_acme"),
                "flyway_schema_history_client").getConfiguration();

        assertThat(Arrays.stream(client.getLocations()).map(Location::getDescriptor))
                .containsExactly("filesystem:/opt/wellnessz/client-migrations");
        assertThat(client.getSchemas()).containsExactly("client_acme");
        assertThat(client.getDefaultSchema()).isEqualTo("client_acme");
        assertThat(client.getTable()).isEqualTo("flyway_schema_history_client");
        assertThat(client.isCreateSchemas()).isTrue();
        assertThat(client.getUrl()).isEqualTo(base.getUrl());
        assertThat(client.getUser()).isEqualTo("wz_migrator");
        assertThat(client.getPlaceholders()).containsEntry("app_role", "wz_app");
        assertThat(client.getJavaMigrations()).isEmpty();
        // product configuration untouched
        assertThat(base.getSchemas()).containsExactly("platform", "masterdata");
        assertThat(base.getTable()).isEqualTo("flyway_schema_history");
    }

    @Test
    void defaultsAndValidation() {
        Configuration base = Flyway.configure().dataSource("jdbc:postgresql://db.invalid/x", "u", "p").load()
                .getConfiguration();
        Configuration client = ClientMigrationsConfig.clientFlyway(base, List.of("filesystem:/x"), List.of(),
                ClientMigrationsConfig.DEFAULT_TABLE).getConfiguration();
        assertThat(client.getSchemas()).containsExactly(ClientMigrationsConfig.DEFAULT_SCHEMA);
        assertThatThrownBy(() -> ClientMigrationsConfig.clientFlyway(base, List.of(), List.of(), "t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void splitTrimsAndDropsBlanks() {
        assertThat(ClientMigrationsConfig.split(" filesystem:/a, ,classpath:db/client/acme,")).containsExactly(
                "filesystem:/a", "classpath:db/client/acme");
        assertThat(ClientMigrationsConfig.split(null)).isEmpty();
    }
}
