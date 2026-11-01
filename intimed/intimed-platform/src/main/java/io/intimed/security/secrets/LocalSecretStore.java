package io.intimed.security.secrets;

import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.TenantKeyService;
import io.intimed.store.DocumentStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** LOCAL secrets: values encrypted with the tenant DEK; only names are listable; every write is audited (never the value). */
public class LocalSecretStore implements SecretStore {
    private final DocumentStore store;
    private final TenantKeyService keys;
    private final AuditService audit;

    public LocalSecretStore(DocumentStore store, TenantKeyService keys, AuditService audit) {
        this.store = store;
        this.keys = keys;
        this.audit = audit;
    }

    @Override public String provider() { return "local-encrypted"; }

    @Override
    public void put(String tenant, String name, String value, String actor) {
        validateName(name);
        store.put(tenant, "secret", name, Json.obj().put("name", name).put("value", keys.encrypt(tenant, "secret/" + name, value))
                .put("updatedAt", Instant.now().toString()).put("updatedBy", actor), "ACTIVE", null);
        audit.record(tenant, actor, "secret.put", "secret/" + name, "{\"provider\":\"local-encrypted\"}");
    }

    @Override
    public Optional<String> get(String tenant, String name) {
        return store.get(tenant, "secret", name).map(d -> keys.decrypt(tenant, "secret/" + name, d.get("value").asString()));
    }

    @Override
    public List<String> names(String tenant) { return store.list(tenant, "secret").stream().map(d -> d.get("name").asString()).toList(); }

    @Override
    public void delete(String tenant, String name, String actor) {
        store.delete(tenant, "secret", name);
        audit.record(tenant, actor, "secret.delete", "secret/" + name, null);
    }

    static void validateName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9._/-]{1,100}")) throw new IllegalArgumentException("invalid secret name");
    }
}
