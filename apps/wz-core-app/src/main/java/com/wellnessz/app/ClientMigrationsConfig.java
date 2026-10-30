package com.wellnessz.app;

import java.util.Arrays;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.JavaMigration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;

/**
 * Client-specific database migrations for a customised deployment.
 *
 * <p>When {@code wellnessz.flyway.client.locations} ({@code WZ_FLYWAY_EXTRA_LOCATIONS}, e.g.
 * {@code filesystem:/opt/wellnessz/client-migrations}) is set, the product migrations run first and the
 * client migrations then run as a second Flyway pass with the same data source, credentials and
 * placeholders but their own schema(s) ({@code WZ_FLYWAY_CLIENT_SCHEMAS}) and history table
 * ({@code WZ_FLYWAY_CLIENT_TABLE}). Separate history keeps client version numbers independent of product
 * releases. Only active when Flyway runs inside the application (Helm {@code migrations.mode=app}); with
 * {@code migrations.mode=job} the Helm migration Job runs the same pass with the Flyway CLI.
 */
@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${wellnessz.flyway.client.locations:}'.isBlank()")
public class ClientMigrationsConfig {

    static final String DEFAULT_SCHEMA = "client_ext";
    static final String DEFAULT_TABLE = "flyway_schema_history_client";

    @Bean
    FlywayMigrationStrategy clientAwareFlywayMigrationStrategy(
            @Value("${wellnessz.flyway.client.locations:}") String locations,
            @Value("${wellnessz.flyway.client.schemas:" + DEFAULT_SCHEMA + "}") String schemas,
            @Value("${wellnessz.flyway.client.table:" + DEFAULT_TABLE + "}") String table) {
        List<String> clientLocations = split(locations);
        List<String> clientSchemas = split(schemas);
        String historyTable = table == null || table.isBlank() ? DEFAULT_TABLE : table.trim();
        return flyway -> {
            flyway.migrate();
            clientFlyway(flyway.getConfiguration(), clientLocations, clientSchemas, historyTable).migrate();
        };
    }

    /** Second Flyway pass derived from the product configuration (data source, user, placeholders). */
    static Flyway clientFlyway(Configuration base, List<String> locations, List<String> schemas, String table) {
        if (locations.isEmpty()) {
            throw new IllegalArgumentException("client migration locations must not be empty");
        }
        List<String> effectiveSchemas = schemas.isEmpty() ? List.of(DEFAULT_SCHEMA) : schemas;
        return Flyway.configure(base.getClassLoader())
                .configuration(base)
                .locations(locations.toArray(String[]::new))
                .schemas(effectiveSchemas.toArray(String[]::new))
                .defaultSchema(effectiveSchemas.get(0))
                .createSchemas(true)
                .table(table)
                // product Java migrations / callbacks / resource providers must not leak into the client pass
                .javaMigrations(new JavaMigration[0])
                .callbacks(new Callback[0])
                .resourceProvider(null)
                .javaMigrationClassProvider(null)
                .load();
    }

    static List<String> split(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
