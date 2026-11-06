package io.intimed.store;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.security.crypto.TenantKeyService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tenant-partitioned document store over PostgreSQL / H2 (control-plane aggregates: configurations, recommendations,
 * runs, certifications, endpoints...). Every call carries the tenant id – there is no method that reads across
 * tenants (SC-18). Kinds that may hold PHI are encrypted as a whole with the tenant DEK (SC-10); only the non-PHI
 * {@code status} and {@code ref} columns are queryable.
 */
@Repository
public class DocumentStore {
    /** Kinds that can contain subject identifiers or measurements → always encrypted at rest. */
    public static final Set<String> SENSITIVE = Set.of("observation", "sandbox-instance", "template-instance", "unmatched", "dlq", "raw", "capture", "worklist", "shadow", "subject", "bed", "enrolment", "delta-last");
    public static final String GLOBAL = "_global";

    private final JdbcClient jdbc;
    private final TenantKeyService keys;

    public DocumentStore(JdbcClient jdbc, TenantKeyService keys) {
        this.jdbc = jdbc;
        this.keys = keys;
    }

    public ObjectNode put(String tenant, String kind, String id, JsonNode data, String status, String ref) {
        requireTenant(tenant);
        String body = Json.write(data);
        if (SENSITIVE.contains(kind)) body = keys.encrypt(tenant, kind + "/" + id, body);
        Instant now = Instant.now();
        int n = jdbc.sql("update documents set data = :d, status = :s, ref = :r, updated_at = :u, version = version + 1 "
                        + "where tenant_id = :t and kind = :k and id = :i")
                .param("d", body).param("s", status).param("r", ref).param("u", java.sql.Timestamp.from(now))
                .param("t", tenant).param("k", kind).param("i", id).update();
        if (n == 0) {
            jdbc.sql("insert into documents(tenant_id, kind, id, data, status, ref, created_at, updated_at, version) "
                            + "values (:t, :k, :i, :d, :s, :r, :u, :u, 1)")
                    .param("t", tenant).param("k", kind).param("i", id).param("d", body).param("s", status)
                    .param("r", ref).param("u", java.sql.Timestamp.from(now)).update();
        }
        return (ObjectNode) data;
    }

    public ObjectNode put(String tenant, String kind, String id, JsonNode data) {
        return put(tenant, kind, id, data, Json.text(data, "status", null), Json.text(data, "ref", null));
    }

    public Optional<ObjectNode> get(String tenant, String kind, String id) {
        requireTenant(tenant);
        return jdbc.sql("select data from documents where tenant_id = :t and kind = :k and id = :i")
                .param("t", tenant).param("k", kind).param("i", id).query(String.class).optional()
                .map(d -> decode(tenant, kind, id, d));
    }

    public ObjectNode require(String tenant, String kind, String id) {
        return get(tenant, kind, id).orElseThrow(() -> ApiException.notFound(kind + " " + id));
    }

    public List<ObjectNode> list(String tenant, String kind) {
        requireTenant(tenant);
        return jdbc.sql("select id, data from documents where tenant_id = :t and kind = :k order by created_at, id")
                .param("t", tenant).param("k", kind)
                .query((rs, i) -> decode(tenant, kind, rs.getString(1), rs.getString(2))).list();
    }

    public List<ObjectNode> listByStatus(String tenant, String kind, String status) {
        requireTenant(tenant);
        return jdbc.sql("select id, data from documents where tenant_id = :t and kind = :k and status = :s order by created_at, id")
                .param("t", tenant).param("k", kind).param("s", status)
                .query((rs, i) -> decode(tenant, kind, rs.getString(1), rs.getString(2))).list();
    }

    public List<ObjectNode> listByRef(String tenant, String kind, String ref) {
        requireTenant(tenant);
        return jdbc.sql("select id, data from documents where tenant_id = :t and kind = :k and ref = :r order by created_at, id")
                .param("t", tenant).param("k", kind).param("r", ref)
                .query((rs, i) -> decode(tenant, kind, rs.getString(1), rs.getString(2))).list();
    }

    public long count(String tenant, String kind) {
        return jdbc.sql("select count(*) from documents where tenant_id = :t and kind = :k").param("t", tenant).param("k", kind)
                .query(Long.class).single();
    }

    public long countByStatus(String tenant, String kind, String status) {
        return jdbc.sql("select count(*) from documents where tenant_id = :t and kind = :k and status = :s")
                .param("t", tenant).param("k", kind).param("s", status).query(Long.class).single();
    }

    public void delete(String tenant, String kind, String id) {
        jdbc.sql("delete from documents where tenant_id = :t and kind = :k and id = :i")
                .param("t", tenant).param("k", kind).param("i", id).update();
    }

    /** Raw stored value – used by the isolation and encryption tests to prove ciphertext at rest. */
    public Optional<String> rawStored(String tenant, String kind, String id) {
        return jdbc.sql("select data from documents where tenant_id = :t and kind = :k and id = :i")
                .param("t", tenant).param("k", kind).param("i", id).query(String.class).optional();
    }

    private ObjectNode decode(String tenant, String kind, String id, String data) {
        String plain = SENSITIVE.contains(kind) ? keys.decrypt(tenant, kind + "/" + id, data) : data;
        return (ObjectNode) Json.parse(plain);
    }

    private static void requireTenant(String tenant) {
        if (tenant == null || tenant.isBlank()) throw new IllegalStateException("tenant context missing");
    }
}
