package io.intimed.security.secrets;

import java.util.List;
import java.util.Optional;

/**
 * One secrets interface for every environment setting (SC-05, IM-E06-BL-004). Configurations, driver packages and
 * exports only ever hold references ({@code secret://<name>}); values are resolved at connection time.
 * Implementations: {@link LocalSecretStore} (LOCAL, AES-GCM with tenant DEK), {@link HashiCorpVaultSecretStore}
 * (Vault KV v2 – usable locally with {@code vault server -dev} and in private cloud); Azure Key Vault and GCP Secret
 * Manager adapters follow the same contract in the cloud releases (see docs/deferred-backlog.md).
 */
public interface SecretStore {
    String PREFIX = "secret://";

    String provider();
    void put(String tenant, String name, String value, String actor);
    Optional<String> get(String tenant, String name);
    List<String> names(String tenant);
    void delete(String tenant, String name, String actor);

    static boolean isRef(String v) { return v != null && v.startsWith(PREFIX); }
    static String name(String ref) { return ref.substring(PREFIX.length()); }
}
