package com.wellnessz.secrets;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Runs after Spring Boot has loaded all configuration and replaces any {@code ENC(...)} / {@code file:} secret
 * references with their resolved values in a highest-precedence, in-memory property source.
 * Resolved values are never logged and the original property sources are left untouched.
 * This is how database connector passwords are kept encrypted at rest in configuration.
 */
public class SecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "wellnessz-resolved-secrets";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        byte[] masterKey = MasterKey.load(name -> {
            String v = environment.getProperty(name);
            return v != null ? v : System.getenv(name);
        });
        SecretValueResolver resolver = new SecretValueResolver(masterKey);
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable) || PROPERTY_SOURCE_NAME.equals(source.getName())) {
                continue;
            }
            for (String name : enumerable.getPropertyNames()) {
                if (resolved.containsKey(name)) {
                    continue; // a higher-precedence source already provided this property
                }
                Object raw = enumerable.getProperty(name);
                if (raw instanceof CharSequence cs && resolver.needsResolution(name, cs.toString())) {
                    String value = resolver.resolve(name, cs.toString());
                    resolved.put(name, value);
                    if (source instanceof SystemEnvironmentPropertySource) {
                        // keep relaxed binding working: SPRING_DATASOURCE_PASSWORD -> spring.datasource.password
                        resolved.putIfAbsent(name.toLowerCase(Locale.ROOT).replace('_', '.'), value);
                    }
                }
            }
        }
        if (!resolved.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
