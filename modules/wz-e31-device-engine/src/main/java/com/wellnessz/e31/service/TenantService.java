package com.wellnessz.e31.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceTenantDirectory;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.security.audit.AuditService;

/**
 * Tenants of the device platform (E31-HLR-12, E31-F12; contract section 12): quota, connector limit, catalogue scope and data
 * residency per tenant ({@code deviceint.tenant_quota}). Implements the kernel {@link DeviceTenantDirectory} read by E32
 * (tenant-scoped catalogue visibility, P6 residency check) and E34 (per-tenant signing keys).
 */
@Service("e31TenantService")
public class TenantService implements DeviceTenantDirectory {

    static final Set<String> SCOPES = Set.of("GROUP", "TENANT");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public TenantService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public Optional<Tenant> tenant(String code) {
        String c = DeviceTenantDirectory.normalise(code);
        return jdbc.query("select code, catalogue_scope, residency_region, messages_per_minute, max_connectors from deviceint.tenant_quota where code = ?",
                (rs, i) -> new Tenant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5)), c).stream().findFirst();
    }

    public List<Map<String, Object>> tenants() {
        return jdbc.queryForList("""
                select code, messages_per_minute as "messagesPerMinute", max_connectors as "maxConnectors", catalogue_scope as "catalogueScope",
                       residency_region as "residencyRegion"
                  from deviceint.tenant_quota order by code""");
    }

    /**
     * Creates or updates a tenant. A new tenant gets the shared group catalogue ({@code GROUP}) and residency {@code UK} unless
     * stated; {@code TENANT} restricts it to its own catalogue rows.
     */
    public Map<String, Object> put(String actor, String code, int messagesPerMinute, int maxConnectors, String catalogueScope, String residencyRegion) {
        if (code == null || !code.matches("[A-Z0-9_]{2,30}") || messagesPerMinute < 1 || maxConnectors < 1) {
            throw ApiException.badRequest("validation_failed", "Invalid tenant quota");
        }
        Optional<Tenant> existing = tenant(code);
        String scope = catalogueScope == null || catalogueScope.isBlank() ? existing.map(Tenant::catalogueScope).orElse("GROUP") : catalogueScope;
        if (!SCOPES.contains(scope)) {
            throw ApiException.badRequest("validation_failed", "catalogueScope must be GROUP or TENANT");
        }
        if (GROUP.equals(code) && !"GROUP".equals(scope)) {
            throw ApiException.badRequest("validation_failed", "The group tenant always uses the group catalogue");
        }
        String residency = residencyRegion == null || residencyRegion.isBlank() ? existing.map(Tenant::residencyRegion).orElse("UK") : residencyRegion;
        if (!residency.matches("[A-Z]{2,10}")) {
            throw ApiException.badRequest("validation_failed", "residencyRegion must be 2–10 capital letters");
        }
        Timestamp now = Timestamp.from(clock.instant());
        int n = jdbc.update("""
                update deviceint.tenant_quota set messages_per_minute = ?, max_connectors = ?, catalogue_scope = ?, residency_region = ?, updated_by = ?,
                    updated_at = ? where code = ?""", messagesPerMinute, maxConnectors, scope, residency, actor, now, code);
        if (n == 0) {
            jdbc.update("""
                    insert into deviceint.tenant_quota (code, messages_per_minute, max_connectors, catalogue_scope, residency_region, updated_by, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?)""", code, messagesPerMinute, maxConnectors, scope, residency, actor, now);
        }
        audit.record("DEVICE_TENANT_QUOTA", "DEVICE_TENANT", code, AuditService.Outcome.SUCCESS,
                "perMinute=" + messagesPerMinute + " scope=" + scope + " residency=" + residency);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("messagesPerMinute", messagesPerMinute);
        out.put("maxConnectors", maxConnectors);
        out.put("catalogueScope", scope);
        out.put("residencyRegion", residency);
        return out;
    }
}
