package com.wellnessz.e34.identity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointStatus;

/** Data access for {@code devicesec.endpoint}, {@code endpoint_instance} and {@code enrolment_token} (bind parameters only). */
@Repository
public class EndpointRepository {

    static final String COLUMNS = "endpoint_id, kind, site_code, zone, tenant_code, status, status_reason, cert_sha256, cert_subject, cert_issuer, "
            + "cert_serial, cert_not_before, cert_not_after, token_hash, token_issued_at, token_expires_at, prev_token_hash, prev_token_valid_until, "
            + "allowed_cidrs, hostname, platform_os, platform_os_version, agent_version, hardening_baseline, hardening_status, attested, "
            + "recertification_required, created_by, created_at, enrolled_at, first_seen_at, last_seen_at, isolated_at, released_at, revoked_at";

    private final JdbcTemplate jdbc;

    public EndpointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Row of {@code devicesec.endpoint}; token hashes stay inside the module (never serialised to clients). */
    public record Endpoint(String endpointId, EndpointKind kind, String siteCode, String zone, String tenantCode, EndpointStatus status,
                           String statusReason, String certSha256, String certSubject, String certIssuer, String certSerial,
                           Instant certNotBefore, Instant certNotAfter, String tokenHash, Instant tokenIssuedAt, Instant tokenExpiresAt,
                           String prevTokenHash, Instant prevTokenValidUntil, List<String> allowedCidrs, String hostname, String platformOs,
                           String platformOsVersion, String agentVersion, String hardeningBaseline, String hardeningStatus, boolean attested,
                           boolean recertificationRequired, String createdBy, Instant createdAt, Instant enrolledAt, Instant firstSeenAt,
                           Instant lastSeenAt, Instant isolatedAt, Instant releasedAt, Instant revokedAt) {

        @Override
        public String toString() {
            return "Endpoint[" + endpointId + ", " + status + "]"; // never print hashes
        }
    }

    public record EnrolmentToken(java.util.UUID id, String endpointId, String kind, Instant expiresAt, Instant usedAt, Instant revokedAt) {
    }

    public Optional<Endpoint> find(String endpointId) {
        return jdbc.query("select " + COLUMNS + " from devicesec.endpoint where endpoint_id = ?", EndpointRepository::map, endpointId)
                .stream().findFirst();
    }

    public Optional<Endpoint> findForUpdate(String endpointId) {
        return jdbc.query("select " + COLUMNS + " from devicesec.endpoint where endpoint_id = ? for update", EndpointRepository::map, endpointId)
                .stream().findFirst();
    }

    public List<Endpoint> list(String siteCode, String status, String kind, int limit) {
        return jdbc.query("select " + COLUMNS + " from devicesec.endpoint where (?::varchar is null or site_code = ?) "
                + "and (?::varchar is null or status = ?) and (?::varchar is null or kind = ?) order by endpoint_id limit ?",
                EndpointRepository::map, siteCode, siteCode, status, status, kind, kind, limit);
    }

    public Set<String> instances(String endpointId) {
        return new LinkedHashSet<>(jdbc.queryForList("select device_instance_id from devicesec.endpoint_instance where endpoint_id = ? "
                + "order by device_instance_id", String.class, endpointId));
    }

    public boolean exists(String endpointId) {
        Integer n = jdbc.queryForObject("select count(*) from devicesec.endpoint where endpoint_id = ?", Integer.class, endpointId);
        return n != null && n > 0;
    }

    public int countWithPrefix(String prefix) {
        Integer n = jdbc.queryForObject("select count(*) from devicesec.endpoint where endpoint_id like ?", Integer.class, prefix + "%");
        return n == null ? 0 : n;
    }

    public void insert(String endpointId, EndpointKind kind, String site, String zone, String tenant, EndpointStatus status,
                       String allowedCidrs, String actor, Instant now) {
        jdbc.update("insert into devicesec.endpoint (endpoint_id, kind, site_code, zone, tenant_code, status, allowed_cidrs, created_by, created_at) "
                + "values (?,?,?,?,?,?,?,?,?)", endpointId, kind.name(), site, zone, tenant, status.name(), allowedCidrs, actor, ts(now));
    }

    public void addInstance(String endpointId, String deviceInstanceId, String actor, Instant now) {
        jdbc.update("insert into devicesec.endpoint_instance (endpoint_id, device_instance_id, added_by, added_at) values (?,?,?,?) "
                + "on conflict do nothing", endpointId, deviceInstanceId, actor, ts(now));
    }

    public int removeInstance(String endpointId, String deviceInstanceId) {
        return jdbc.update("delete from devicesec.endpoint_instance where endpoint_id = ? and device_instance_id = ?", endpointId, deviceInstanceId);
    }

    public void insertEnrolmentToken(java.util.UUID id, String endpointId, String tokenHash, EndpointKind kind, String actor, Instant now,
                                     Instant expiresAt) {
        jdbc.update("insert into devicesec.enrolment_token (id, endpoint_id, token_hash, kind, created_by, created_at, expires_at) "
                + "values (?,?,?,?,?,?,?)", id, endpointId, tokenHash, kind.name(), actor, ts(now), ts(expiresAt));
    }

    public int revokeOpenEnrolmentTokens(String endpointId, Instant now) {
        return jdbc.update("update devicesec.enrolment_token set revoked_at = ? where endpoint_id = ? and used_at is null and revoked_at is null",
                ts(now), endpointId);
    }

    public Optional<EnrolmentToken> findEnrolmentTokenForUpdate(String tokenHash) {
        return jdbc.query("select id, endpoint_id, kind, expires_at, used_at, revoked_at from devicesec.enrolment_token where token_hash = ? for update",
                (rs, i) -> new EnrolmentToken(rs.getObject("id", java.util.UUID.class), rs.getString("endpoint_id"), rs.getString("kind"),
                        inst(rs, "expires_at"), inst(rs, "used_at"), inst(rs, "revoked_at")), tokenHash).stream().findFirst();
    }

    public void markEnrolmentTokenUsed(java.util.UUID id, Instant now, String from) {
        jdbc.update("update devicesec.enrolment_token set used_at = ?, used_from = ? where id = ?", ts(now), from, id);
    }

    public Optional<Instant> openEnrolmentExpiry(String endpointId) {
        return jdbc.query("select max(expires_at) as e from devicesec.enrolment_token where endpoint_id = ? and used_at is null and revoked_at is null",
                (rs, i) -> inst(rs, "e"), endpointId).stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public void bindEnrolment(String endpointId, CertificateVerifier.CertificateInfo cert, String tokenHash, Instant now, Instant tokenExpiresAt,
                              String hostname, String os, String osVersion, String agentVersion, String baseline, String hardeningStatus,
                              boolean attested) {
        jdbc.update("update devicesec.endpoint set status = 'ACTIVE', status_reason = 'ENROLLED', cert_sha256 = ?, cert_subject = ?, cert_issuer = ?, "
                + "cert_serial = ?, cert_not_before = ?, cert_not_after = ?, token_hash = ?, token_issued_at = ?, token_expires_at = ?, "
                + "prev_token_hash = null, prev_token_valid_until = null, hostname = ?, platform_os = ?, platform_os_version = ?, agent_version = ?, "
                + "hardening_baseline = ?, hardening_status = ?, attested = ?, enrolled_at = ?, version = version + 1 where endpoint_id = ?",
                cert.sha256(), cert.subject(), cert.issuer(), cert.serial(), ts(cert.notBefore()), ts(cert.notAfter()), tokenHash, ts(now),
                ts(tokenExpiresAt), hostname, os, osVersion, agentVersion, baseline, hardeningStatus, attested, ts(now), endpointId);
    }

    public void bindCertificate(String endpointId, CertificateVerifier.CertificateInfo cert) {
        jdbc.update("update devicesec.endpoint set cert_sha256 = ?, cert_subject = ?, cert_issuer = ?, cert_serial = ?, cert_not_before = ?, "
                + "cert_not_after = ?, version = version + 1 where endpoint_id = ?", cert.sha256(), cert.subject(), cert.issuer(), cert.serial(),
                ts(cert.notBefore()), ts(cert.notAfter()), endpointId);
    }

    public void rotateToken(String endpointId, String newHash, Instant now, Instant expiresAt, String prevHash, Instant prevValidUntil) {
        jdbc.update("update devicesec.endpoint set token_hash = ?, token_issued_at = ?, token_expires_at = ?, prev_token_hash = ?, "
                + "prev_token_valid_until = ?, version = version + 1 where endpoint_id = ?", newHash, ts(now), ts(expiresAt), prevHash,
                ts(prevValidUntil), endpointId);
    }

    public void setStatus(String endpointId, EndpointStatus status, String reason, Instant now) {
        boolean recert = status == EndpointStatus.ISOLATED || status == EndpointStatus.REVOKED;
        switch (status) {
            case ISOLATED -> jdbc.update("update devicesec.endpoint set status = ?, status_reason = ?, recertification_required = ?, isolated_at = ?, "
                    + "version = version + 1 where endpoint_id = ?", status.name(), reason, recert, ts(now), endpointId);
            case REVOKED -> jdbc.update("update devicesec.endpoint set status = ?, status_reason = ?, recertification_required = ?, revoked_at = ?, "
                    + "version = version + 1 where endpoint_id = ?", status.name(), reason, recert, ts(now), endpointId);
            case ACTIVE -> jdbc.update("update devicesec.endpoint set status = ?, status_reason = ?, recertification_required = ?, released_at = ?, "
                    + "version = version + 1 where endpoint_id = ?", status.name(), reason, recert, ts(now), endpointId);
            case PENDING_ENROLMENT -> jdbc.update("update devicesec.endpoint set status = ?, status_reason = ?, recertification_required = ?, "
                    + "version = version + 1 where endpoint_id = ?", status.name(), reason, recert, endpointId);
        }
    }

    public int markFirstSeen(String endpointId, Instant now) {
        return jdbc.update("update devicesec.endpoint set first_seen_at = ? where endpoint_id = ? and first_seen_at is null", ts(now), endpointId);
    }

    public void touchLastSeen(String endpointId, Instant at) {
        jdbc.update("update devicesec.endpoint set last_seen_at = ? where endpoint_id = ? and (last_seen_at is null or last_seen_at < ?)",
                ts(at), endpointId, ts(at));
    }

    public List<Endpoint> expiringCertificates(Instant before) {
        return jdbc.query("select " + COLUMNS + " from devicesec.endpoint where status = 'ACTIVE' and cert_not_after is not null "
                + "and cert_not_after < ? order by cert_not_after", EndpointRepository::map, ts(before));
    }

    static Endpoint map(ResultSet rs, int i) throws SQLException {
        String cidrs = rs.getString("allowed_cidrs");
        return new Endpoint(rs.getString("endpoint_id"), EndpointKind.valueOf(rs.getString("kind")), rs.getString("site_code"),
                rs.getString("zone"), rs.getString("tenant_code"), EndpointStatus.valueOf(rs.getString("status")), rs.getString("status_reason"),
                rs.getString("cert_sha256"), rs.getString("cert_subject"), rs.getString("cert_issuer"), rs.getString("cert_serial"),
                inst(rs, "cert_not_before"), inst(rs, "cert_not_after"), rs.getString("token_hash"), inst(rs, "token_issued_at"),
                inst(rs, "token_expires_at"), rs.getString("prev_token_hash"), inst(rs, "prev_token_valid_until"),
                cidrs == null || cidrs.isBlank() ? List.of() : Arrays.stream(cidrs.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList(),
                rs.getString("hostname"), rs.getString("platform_os"), rs.getString("platform_os_version"), rs.getString("agent_version"),
                rs.getString("hardening_baseline"), rs.getString("hardening_status"), rs.getBoolean("attested"),
                rs.getBoolean("recertification_required"), rs.getString("created_by"), inst(rs, "created_at"), inst(rs, "enrolled_at"),
                inst(rs, "first_seen_at"), inst(rs, "last_seen_at"), inst(rs, "isolated_at"), inst(rs, "released_at"), inst(rs, "revoked_at"));
    }

    public static Instant inst(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
