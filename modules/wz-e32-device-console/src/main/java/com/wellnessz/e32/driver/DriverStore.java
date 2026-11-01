package com.wellnessz.e32.driver;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/** Data access for the driver-package registry ({@code devicecat.driver_package}); versions are immutable. */
@Component("e32DriverStore")
public class DriverStore {

    private static final String COLUMNS = """
            select package_id, version, family, adapter, models, options, engine_range, manifest, sha256, signature_key_id, signature_algorithm,
                   signature_value, status, conformance_run_id, conformance_evidence_sha256, registered_by, registered_at, certified_by, certified_at,
                   status_changed_by, status_changed_at, status_reason, tenant_code from devicecat.driver_package""";

    private final E32Support support;

    public DriverStore(E32Support support) {
        this.support = support;
    }

    public record DriverRow(String packageId, String version, String family, String adapter, List<String> models, List<String> options,
                            String engineRange, String manifestJson, String sha256, String signatureKeyId, String signatureAlgorithm,
                            String signatureValue, String status, String conformanceRunId, String conformanceEvidenceSha256, String registeredBy,
                            Instant registeredAt, String certifiedBy, Instant certifiedAt, String statusChangedBy, Instant statusChangedAt,
                            String statusReason, String tenantCode) {

        public DriverRow {
            models = List.copyOf(models);
            options = List.copyOf(options);
        }

        public boolean usable(boolean allowRegistered) {
            return "CERTIFIED".equals(status) || allowRegistered && "REGISTERED".equals(status);
        }

        public String ref() {
            return packageId + "@" + version;
        }
    }

    public List<DriverRow> all() {
        return support.jdbc().query(COLUMNS + " order by package_id, registered_at", this::map);
    }

    public Optional<DriverRow> find(String packageId, String version) {
        return support.jdbc().query(COLUMNS + " where package_id = ? and version = ?", this::map, packageId, version).stream().findFirst();
    }

    public DriverRow require(String packageId, String version) {
        return find(packageId, version).orElseThrow(() -> ApiException.notFound("Driver package"));
    }

    public List<DriverRow> forModel(String modelCode) {
        return all().stream().filter(d -> d.models().contains(modelCode)).toList();
    }

    /** Best package for (model, option): newest CERTIFIED, else (if allowed) newest REGISTERED. */
    public Optional<DriverRow> best(String modelCode, String option, boolean allowRegistered) {
        return best(modelCode, option, allowRegistered, null);
    }

    /** As {@link #best(String, String, boolean)}, limited to packages visible to a tenant (shared or its own). */
    public Optional<DriverRow> best(String modelCode, String option, boolean allowRegistered, java.util.function.Predicate<String> tenantVisible) {
        Comparator<DriverRow> byVersion = (a, b) -> DriverPackageRules.compareVersions(a.version(), b.version());
        List<DriverRow> candidates = forModel(modelCode).stream().filter(d -> d.options().contains(option))
                .filter(d -> tenantVisible == null || tenantVisible.test(d.tenantCode())).toList();
        Optional<DriverRow> certified = candidates.stream().filter(d -> "CERTIFIED".equals(d.status())).max(byVersion);
        if (certified.isPresent() || !allowRegistered) {
            return certified;
        }
        return candidates.stream().filter(d -> "REGISTERED".equals(d.status())).max(byVersion);
    }

    public boolean exists(String packageId, String version) {
        return find(packageId, version).isPresent();
    }

    public void insert(Map<String, Object> pkg, String canonical, String sha256, Map<String, Object> signature, String status, String conformanceRunId,
                       String evidenceSha, String actor) {
        insert(pkg, canonical, sha256, signature, status, conformanceRunId, evidenceSha, actor, null);
    }

    /** @param tenantCode owning tenant of a tenant-scoped package ({@code null} = shared group catalogue) */
    public void insert(Map<String, Object> pkg, String canonical, String sha256, Map<String, Object> signature, String status, String conformanceRunId,
                       String evidenceSha, String actor, String tenantCode) {
        Instant now = support.now();
        support.jdbc().update("""
                insert into devicecat.driver_package (package_id, version, family, adapter, models, options, engine_range, manifest, sha256,
                    signature_key_id, signature_algorithm, signature_value, status, conformance_run_id, conformance_evidence_sha256, registered_by,
                    registered_at, certified_by, certified_at, tenant_code)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                Docs.str(pkg.get("packageId")), Docs.str(pkg.get("version")), Docs.str(pkg.get("family")), Docs.str(pkg.get("adapter")),
                Docs.joinCsv(Docs.strings(pkg.get("models"))), Docs.joinCsv(Docs.strings(pkg.get("options"))), Docs.str(pkg.get("engineCompatibility")),
                canonical, sha256, Docs.str(signature.get("keyId")), Docs.str(signature.get("algorithm")), Docs.str(signature.get("value")), status,
                conformanceRunId, evidenceSha, actor, Timestamp.from(now), "CERTIFIED".equals(status) ? actor : null,
                "CERTIFIED".equals(status) ? Timestamp.from(now) : null, tenantCode);
    }

    public void certify(DriverRow d, String actor) {
        support.jdbc().update("""
                update devicecat.driver_package set status = 'CERTIFIED', certified_by = ?, certified_at = ?, status_changed_by = ?, status_changed_at = ?
                where package_id = ? and version = ?""", actor, Timestamp.from(support.now()), actor, Timestamp.from(support.now()), d.packageId(), d.version());
    }

    public void setStatus(DriverRow d, String status, String reason, String actor) {
        support.jdbc().update("""
                update devicecat.driver_package set status = ?, status_reason = ?, status_changed_by = ?, status_changed_at = ?
                where package_id = ? and version = ?""", status, reason, actor, Timestamp.from(support.now()), d.packageId(), d.version());
    }

    /** The manifest including its signature member (as published to the engine). */
    public Map<String, Object> manifest(DriverRow d) {
        Map<String, Object> m = support.readMap(d.manifestJson());
        if (d.signatureValue() != null) {
            m.put("signature", Docs.of("keyId", d.signatureKeyId(), "algorithm", d.signatureAlgorithm(), "value", d.signatureValue()));
        }
        return m;
    }

    private static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private DriverRow map(ResultSet rs, int i) throws SQLException {
        return new DriverRow(rs.getString("package_id"), rs.getString("version"), rs.getString("family"), rs.getString("adapter"),
                Docs.csv(rs.getString("models")), Docs.csv(rs.getString("options")), rs.getString("engine_range"), rs.getString("manifest"),
                rs.getString("sha256"), rs.getString("signature_key_id"), rs.getString("signature_algorithm"), rs.getString("signature_value"),
                rs.getString("status"), rs.getString("conformance_run_id"), rs.getString("conformance_evidence_sha256"), rs.getString("registered_by"),
                inst(rs, "registered_at"), rs.getString("certified_by"), inst(rs, "certified_at"), rs.getString("status_changed_by"),
                inst(rs, "status_changed_at"), rs.getString("status_reason"), rs.getString("tenant_code"));
    }
}
