package io.intimed.tenancy;

import io.intimed.common.ApiException;
import io.intimed.common.Json;
import io.intimed.security.audit.AuditService;
import io.intimed.security.crypto.TenantKeyService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tenants, residency pinning, entitlements and metering (IM-E01-F12 / IM-E01-BL-028, IM-E09 local subset). Tenant
 * settings carry the context that configuration intelligence reads (network profile, security policy, jurisdiction,
 * unit policy) – see architecture §6.1.
 */
@Service
public class TenantService {
    public static final Set<String> RESIDENCIES = Set.of("eu-west", "eu-central", "uk-south", "us-east", "me-central", "ap-southeast", "client-hosted");
    private final JdbcClient jdbc;
    private final AuditService audit;
    private final TenantKeyService keys;
    private final io.intimed.store.DocumentStore store;

    public TenantService(JdbcClient jdbc, AuditService audit, TenantKeyService keys, io.intimed.store.DocumentStore store) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.keys = keys;
        this.store = store;
    }

    public record Tenant(String id, String name, String residency, String plan, String status, String environment, ObjectNode settings) { }

    public Tenant create(String id, String name, String residency, String plan, ObjectNode settings, String actor) {
        if (!id.matches("[a-z0-9][a-z0-9-]{2,40}")) throw ApiException.bad("invalid-tenant-id", "tenant id must be lower-case kebab");
        if (!RESIDENCIES.contains(residency)) throw ApiException.bad("invalid-residency", "unknown residency region");
        if (find(id) != null) throw ApiException.conflict("tenant-exists", "tenant exists");
        ObjectNode s = defaults();
        if (settings != null) s.setAll(settings);
        jdbc.sql("insert into tenants(id, name, residency, plan, status, environment, settings, created_at) values (:i,:n,:r,:p,'ACTIVE','LOCAL',:s,:c)")
                .param("i", id).param("n", name).param("r", residency).param("p", plan).param("s", Json.write(s))
                .param("c", java.sql.Timestamp.from(Instant.now())).update();
        keys.dek(id);
        store.put(io.intimed.store.DocumentStore.GLOBAL, "tenant-index", id, Json.obj().put("id", id), "ACTIVE", null);
        audit.record(id, actor, "tenant.create", "tenant/" + id, Map.of("residency", residency, "plan", plan));
        return find(id);
    }

    public static ObjectNode defaults() {
        ObjectNode s = Json.obj();
        s.set("networkProfile", Json.obj().put("gatewayInDeviceZone", true).put("deviceZone", "MDZ-1").put("ntp", "time.intimed.local"));
        s.set("securityPolicy", Json.obj().put("tlsMin", "1.2").put("blockPlaintextMllp", true).put("blockJustWorks", true)
                .put("blockBroadCloudScopes", true).put("certMinDays", 30).put("postureGate", "BLOCK"));
        s.set("unitPolicy", Json.obj().put("temperature", "Cel").put("pressure", "mm[Hg]").put("glucose", "mmol/L").put("weight", "kg"));
        s.put("jurisdiction", "EU").put("locale", "en-GB").put("knowledgeBaseOptIn", true);
        s.set("quotas", Json.obj().put("messagesPerSecond", 500).put("connectedDevices", 10000).put("burst", 1000));
        s.set("entitlements", Json.arr().add("ENGINE").add("CATALOGUE").add("CONSOLE").add("INTELLIGENCE").add("HARNESS").add("TEMPLATES").add("FHIR_API"));
        return s;
    }

    public Tenant find(String id) {
        return jdbc.sql("select id, name, residency, plan, status, environment, settings from tenants where id = :i").param("i", id)
                .query((rs, n) -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), (ObjectNode) Json.parse(rs.getString(7)))).optional().orElse(null);
    }

    public Tenant require(String id) {
        Tenant t = find(id);
        if (t == null) throw ApiException.notFound("tenant");
        if (!"ACTIVE".equals(t.status())) throw ApiException.forbidden("tenant-suspended", "tenant is not active");
        return t;
    }

    public List<Tenant> all() {
        return jdbc.sql("select id from tenants order by id").query(String.class).list().stream().map(this::find).toList();
    }

    public void updateSettings(String id, ObjectNode settings, String actor) {
        Tenant t = require(id);
        ObjectNode s = (ObjectNode) t.settings().deepCopy();
        s.setAll(settings);
        jdbc.sql("update tenants set settings = :s where id = :i").param("s", Json.write(s)).param("i", id).update();
        audit.record(id, actor, "tenant.settings", "tenant/" + id, settings);
    }

    public boolean entitled(String tenant, String feature) {
        Tenant t = require(tenant);
        for (var e : t.settings().path("entitlements")) if (e.asString().equals(feature)) return true;
        return false;
    }

    public void requireEntitlement(String tenant, String feature) {
        if (!entitled(tenant, feature)) throw ApiException.forbidden("not-entitled", "tenant licence does not include " + feature);
    }

    // ---------------------------------------------------------------- metering (IM-E09-BL-002 local subset)
    public void meter(String tenant, String meter, long qty) {
        String day = LocalDate.now().toString();
        int n = jdbc.sql("update metering set qty = qty + :q where tenant_id = :t and usage_day = :d and meter = :m")
                .param("q", qty).param("t", tenant).param("d", day).param("m", meter).update();
        if (n == 0) {
            try {
                jdbc.sql("insert into metering(tenant_id, usage_day, meter, qty) values (:t,:d,:m,:q)")
                        .param("t", tenant).param("d", day).param("m", meter).param("q", qty).update();
            } catch (RuntimeException race) {
                jdbc.sql("update metering set qty = qty + :q where tenant_id = :t and usage_day = :d and meter = :m")
                        .param("q", qty).param("t", tenant).param("d", day).param("m", meter).update();
            }
        }
    }

    public List<Map<String, Object>> metering(String tenant) {
        return jdbc.sql("select usage_day, meter, qty from metering where tenant_id = :t order by usage_day desc, meter").param("t", tenant)
                .query((rs, i) -> Map.<String, Object>of("day", rs.getString(1), "meter", rs.getString(2), "qty", rs.getLong(3))).list();
    }

    /** Offboarding: export + crypto-shred evidence (IM-E09-BL-001 local subset). */
    public Map<String, Object> offboard(String id, String actor) {
        require(id);
        jdbc.sql("update tenants set status = 'OFFBOARDED' where id = :i").param("i", id).update();
        long docs = jdbc.sql("delete from documents where tenant_id = :i").param("i", id).update();
        keys.shred(id);
        audit.record(id, actor, "tenant.offboard", "tenant/" + id, Map.of("documentsDeleted", docs, "dek", "shredded"));
        return Map.of("tenant", id, "documentsDeleted", docs, "dataKey", "shredded", "at", Instant.now().toString());
    }
}
