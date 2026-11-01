package io.intimed.config;

import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.TenantKeyService;
import io.intimed.security.secrets.HashiCorpVaultSecretStore;
import io.intimed.security.secrets.LocalSecretStore;
import io.intimed.security.secrets.SecretStore;
import io.intimed.store.DocumentStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wiring of environment-specific adapters behind one interface (ADR-08): secrets provider selection. */
@Configuration
@EnableScheduling
public class PlatformConfig {
    @Bean
    SecretStore secretStore(@Value("${intimed.secrets.provider:local}") String provider, @Value("${intimed.secrets.vault.address:http://127.0.0.1:8200}") String addr,
                            @Value("${intimed.secrets.vault.mount:secret}") String mount, DocumentStore store, TenantKeyService keys, AuditService audit) {
        if ("vault".equals(provider)) return new HashiCorpVaultSecretStore(addr, mount, System.getenv("VAULT_TOKEN"), audit);
        return new LocalSecretStore(store, keys, audit);
    }
}
