package com.wellnessz.e34.identity;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointStatus;
import com.wellnessz.casekernel.device.DeviceEndpointProvisioning;
import com.wellnessz.casekernel.device.DeviceEndpointStatusListener;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e34.anomaly.AlertService;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.identity.EndpointRepository.Endpoint;
import com.wellnessz.e34.incident.IncidentService;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.SecurityNotifications;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.security.audit.AuditService;

/**
 * Endpoint identity registry and life-cycle (E34-HLR-01, -09; contract §15.1, §15.8): creation with a one-time enrolment
 * token, token rotation with grace period, isolation (playbook, manual or automatic), release behind the re-certification
 * gate, revocation. Implements {@link DeviceEndpointProvisioning} for the console (E32) and notifies E31 / E32 through
 * {@link DeviceEndpointStatusListener} after commit.
 */
@Service
public class EndpointService implements DeviceEndpointProvisioning {

    private static final Logger log = LoggerFactory.getLogger(EndpointService.class);
    static final Pattern INSTANCE_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    static final Pattern TENANT = Pattern.compile("[A-Z0-9_]{2,30}");
    static final Pattern ENDPOINT_ID = Pattern.compile("[A-Za-z0-9._\\-]{1,64}");
    static final Set<String> ZONES = Set.of("DEVICE_VLAN", "GATEWAY_ZONE", "CLINIC_LAN", "VENDOR_CLOUD", "CORE");
    static final Pattern REASON = Pattern.compile("[A-Z][A-Z0-9_]{2,59}");

    public record CertificateView(String sha256, String subject, String issuer, String serial, Instant notBefore, Instant notAfter) {
    }

    public record EndpointView(String endpointId, EndpointKind kind, String siteCode, String zone, String tenantCode, EndpointStatus status,
                               String statusReason, CertificateView certificate, Long certificateExpiresInDays, Instant tokenIssuedAt,
                               Instant tokenExpiresAt, Instant enrolmentExpiresAt, Set<String> deviceInstanceIds, List<String> allowedCidrs,
                               String hostname, String platformOs, String platformOsVersion, String agentVersion, String hardeningBaseline,
                               String hardeningStatus, boolean attested, boolean recertificationRequired, String createdBy, Instant createdAt,
                               Instant enrolledAt, Instant firstSeenAt, Instant lastSeenAt, Instant isolatedAt, Instant releasedAt,
                               Instant revokedAt) {
    }

    public record CreateCommand(EndpointKind kind, String siteCode, String zone, String tenantCode, List<String> deviceInstanceIds,
                                List<String> allowedCidrs) {
    }

    /** @param enrolmentToken shown once, never stored in plaintext */
    public record Issued(String endpointId, EndpointStatus status, String enrolmentToken, Instant enrolmentExpiresAt) {

        @Override
        public String toString() {
            return "Issued[" + endpointId + "]";
        }
    }

    /** @param endpointToken shown once; the previous token stays valid until {@code previousTokenValidUntil} */
    public record Rotated(String endpointId, String endpointToken, Instant tokenExpiresAt, Instant previousTokenValidUntil) {

        @Override
        public String toString() {
            return "Rotated[" + endpointId + "]";
        }
    }

    public record StatusChange(String endpointId, EndpointStatus status, String reasonCode, Instant detectedAt, Instant changedAt,
                               Double minutesToIsolate, String incidentRef) {
    }

    private final EndpointRepository repo;
    private final JdbcTemplate jdbc;
    private final TokenHasher hasher;
    private final SecurityEventService events;
    private final AlertService alerts;
    private final IncidentService incidents;
    private final SecurityNotifications notify;
    private final AuditService audit;
    private final DeviceSpis spis;
    private final E34Settings settings;

    public EndpointService(EndpointRepository repo, JdbcTemplate jdbc, TokenHasher hasher, SecurityEventService events, AlertService alerts,
                           IncidentService incidents, SecurityNotifications notify, AuditService audit, DeviceSpis spis, E34Settings settings) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.hasher = hasher;
        this.events = events;
        this.alerts = alerts;
        this.incidents = incidents;
        this.notify = notify;
        this.audit = audit;
        this.spis = spis;
        this.settings = settings;
    }

    // ------------------------------------------------------------------ registry

    @Transactional(readOnly = true)
    public List<EndpointView> list(String siteCode, String status, String kind, int limit) {
        settings.requireEnabled();
        return repo.list(siteCode, status, kind, Math.min(Math.max(limit, 1), 500)).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public EndpointView get(String endpointId) {
        settings.requireEnabled();
        return view(repo.find(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint")));
    }

    @Transactional
    public Issued create(String actor, CreateCommand cmd) {
        settings.requireEnabled();
        if (cmd.kind() == null || !EndpointPolicy.validSite(cmd.siteCode())) {
            throw ApiException.badRequest("validation_failed", "Endpoint kind and site code are required");
        }
        String zone = cmd.zone() == null || cmd.zone().isBlank() ? EndpointPolicy.defaultZone(cmd.kind()) : cmd.zone();
        if (!ZONES.contains(zone)) {
            throw ApiException.badRequest("validation_failed", "Unknown zone");
        }
        if (EndpointPolicy.deviceInDeviceVlan(cmd.kind(), zone)) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "zone_violation",
                    "Devices in the device VLAN never reach the core directly – connect them through a gateway");
        }
        String tenant = cmd.tenantCode() == null || cmd.tenantCode().isBlank() ? "GROUP" : cmd.tenantCode();
        if (!TENANT.matcher(tenant).matches()) {
            throw ApiException.badRequest("validation_failed", "Invalid tenant code");
        }
        List<String> instances = cmd.deviceInstanceIds() == null ? List.of() : cmd.deviceInstanceIds();
        if (instances.size() > 50 || instances.stream().anyMatch(i -> i == null || !INSTANCE_ID.matcher(i).matches())) {
            throw ApiException.badRequest("validation_failed", "Invalid device instance ids");
        }
        List<String> cidrs = cmd.allowedCidrs() == null ? List.of() : cmd.allowedCidrs();
        if (cidrs.size() > 10 || cidrs.stream().anyMatch(c -> !EndpointPolicy.validCidr(c))) {
            throw ApiException.badRequest("validation_failed", "Invalid CIDR ranges");
        }
        Instant now = settings.now();
        boolean vendorCloud = cmd.kind() == EndpointKind.VENDOR_CLOUD;
        String endpointId = insertWithNewId(cmd.kind(), cmd.siteCode(), zone, tenant, vendorCloud ? EndpointStatus.ACTIVE : EndpointStatus.PENDING_ENROLMENT,
                cidrs.isEmpty() ? null : String.join(",", cidrs.stream().map(String::strip).toList()), actor, now);
        for (String i : instances) {
            repo.addInstance(endpointId, i, actor, now);
        }
        String token = null;
        Instant expires = null;
        if (!vendorCloud) {
            token = hasher.newToken();
            expires = now.plus(settings.enrolmentTokenHours(), ChronoUnit.HOURS);
            repo.insertEnrolmentToken(UUID.randomUUID(), endpointId, hasher.hash(token), cmd.kind(), actor, now, expires);
        }
        audit.record("DEVICE_ENDPOINT_CREATE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, cmd.kind().name());
        events.record("ENDPOINT_CREATED", DeviceCodes.Severity.INFO, endpointId, null, actor, null, Map.of("kind", cmd.kind().name()));
        return new Issued(endpointId, vendorCloud ? EndpointStatus.ACTIVE : EndpointStatus.PENDING_ENROLMENT, token, expires);
    }

    private String insertWithNewId(EndpointKind kind, String site, String zone, String tenant, EndpointStatus status, String cidrs, String actor,
                                   Instant now) {
        String prefix = "EP-" + site + "-" + EndpointPolicy.kindCode(kind) + "-";
        int n = repo.countWithPrefix(prefix) + 1;
        for (int attempt = 0; attempt < 50; attempt++, n++) {
            String id = prefix + (n < 10 ? "0" + n : String.valueOf(n));
            if (!repo.exists(id)) {
                try {
                    repo.insert(id, kind, site, zone, tenant, status, cidrs, actor, now);
                    return id;
                } catch (DuplicateKeyException e) {
                    // concurrent creation – try the next number
                }
            }
        }
        throw ApiException.conflict("endpoint_id_exhausted", "No free endpoint id");
    }

    /** New one-time enrolment token for an endpoint still waiting for enrolment; earlier open tokens are revoked. */
    @Transactional
    public Issued reissueEnrolmentToken(String actor, String endpointId) {
        settings.requireEnabled();
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        if (ep.status() != EndpointStatus.PENDING_ENROLMENT) {
            throw ApiException.conflict("already_enrolled", "The endpoint is already enrolled");
        }
        Instant now = settings.now();
        repo.revokeOpenEnrolmentTokens(endpointId, now);
        String token = hasher.newToken();
        Instant expires = now.plus(settings.enrolmentTokenHours(), ChronoUnit.HOURS);
        repo.insertEnrolmentToken(UUID.randomUUID(), endpointId, hasher.hash(token), ep.kind(), actor, now, expires);
        audit.record("DEVICE_ENDPOINT_ROTATE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, "ENROLMENT_TOKEN");
        events.record("TOKEN_ROTATED", DeviceCodes.Severity.LOW, endpointId, null, actor, "enrolment_token_reissued", Map.of());
        return new Issued(endpointId, ep.status(), token, expires);
    }

    @Transactional
    public Rotated rotateToken(String actor, String endpointId) {
        settings.requireEnabled();
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        if (ep.status() != EndpointStatus.ACTIVE || ep.tokenHash() == null) {
            throw ApiException.conflict("endpoint_not_active", "Only active, enrolled endpoints can rotate their token");
        }
        Instant now = settings.now();
        String token = hasher.newToken();
        Instant expires = now.plus(settings.endpointTokenDays(), ChronoUnit.DAYS);
        Instant grace = now.plus(settings.tokenGraceMinutes(), ChronoUnit.MINUTES);
        repo.rotateToken(endpointId, hasher.hash(token), now, expires, ep.tokenHash(), grace);
        audit.record("DEVICE_ENDPOINT_ROTATE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, "ENDPOINT_TOKEN");
        events.record("TOKEN_ROTATED", DeviceCodes.Severity.LOW, endpointId, null, actor, null, Map.of());
        return new Rotated(endpointId, token, expires, grace);
    }

    // ------------------------------------------------------------------ isolation playbook

    @Transactional
    public StatusChange isolate(String actor, String endpointId, String reasonCode, String note) {
        settings.requireEnabled();
        return doIsolate(actor, endpointId, reason(reasonCode, "MANUAL_ISOLATION"), null, settings.now(), true);
    }

    /** Automatic isolation by an anomaly rule or the isolation sweep; idempotent. */
    @Transactional
    public StatusChange isolateAutomatically(String endpointId, UUID alertId, String reasonCode, Instant detectedAt) {
        return doIsolate("system:anomaly", endpointId, reasonCode, alertId, detectedAt, false);
    }

    private StatusChange doIsolate(String actor, String endpointId, String reasonCode, UUID alertId, Instant detectedAt, boolean manual) {
        jdbc.execute("set local lock_timeout = '5s'");
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        Instant now = settings.now();
        if (ep.status() == EndpointStatus.ISOLATED || ep.status() == EndpointStatus.REVOKED) {
            if (manual && ep.status() == EndpointStatus.REVOKED) {
                throw ApiException.conflict("endpoint_revoked", "The endpoint is revoked");
            }
            alerts.markIsolated(endpointId, ep.status() == EndpointStatus.ISOLATED && ep.isolatedAt() != null ? ep.isolatedAt() : now);
            return new StatusChange(endpointId, ep.status(), ep.statusReason(), detectedAt, ep.isolatedAt(), null, null);
        }
        repo.setStatus(endpointId, EndpointStatus.ISOLATED, reasonCode, now);
        List<UUID> alertIds = new ArrayList<>(alerts.markIsolated(endpointId, now));
        if (alertId != null && !alertIds.contains(alertId)) {
            alertIds.add(alertId);
        }
        String incidentRef = incidents.openForIsolation(endpointId, alertIds, detectedAt, now, actor, reasonCode);
        jdbc.update("insert into devicesec.isolation (id, endpoint_id, alert_id, reason_code, actor, detected_at, isolated_at, incident_ref) "
                + "values (?,?,?,?,?,?,?,?)", UUID.randomUUID(), endpointId, alertId, reasonCode, actor, EndpointRepository.ts(detectedAt),
                EndpointRepository.ts(now), incidentRef);
        Set<String> instances = repo.instances(endpointId);
        double minutes = Duration.between(detectedAt, now).toMillis() / 60000.0;
        audit.recordAs(actor, "", "DEVICE_ENDPOINT_ISOLATE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, reasonCode);
        events.record("ENDPOINT_ISOLATED", DeviceCodes.Severity.HIGH, endpointId, null, actor, reasonCode,
                Map.of("incidentRef", incidentRef, "minutes", String.format(java.util.Locale.ROOT, "%.2f", minutes)));
        notify.isolated(endpointId, ep.siteCode());
        afterCommit(endpointId, instances, EndpointStatus.ISOLATED, reasonCode);
        return new StatusChange(endpointId, EndpointStatus.ISOLATED, reasonCode, detectedAt, now, minutes, incidentRef);
    }

    /** Release behind the re-certification gate: every instance must be published again by the console (E32). */
    @Transactional
    public StatusChange release(String actor, String endpointId, String reasonCode, String note) {
        settings.requireEnabled();
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        if (ep.status() != EndpointStatus.ISOLATED) {
            throw ApiException.conflict("endpoint_not_isolated", "Only isolated endpoints can be released");
        }
        Set<String> instances = repo.instances(endpointId);
        DeviceCatalogueLookup catalogue = spis.catalogue();
        for (String i : instances) {
            if (catalogue.instance(i).filter(DeviceCatalogueLookup.DeviceInstance::published).isEmpty()) {
                audit.record("DEVICE_ENDPOINT_RELEASE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.DENIED, "recertification_required");
                throw ApiException.conflict("recertification_required",
                        "Every device instance of the endpoint must be re-certified in the console before release");
            }
        }
        Instant now = settings.now();
        String reason = reason(reasonCode, "RELEASED");
        repo.setStatus(endpointId, EndpointStatus.ACTIVE, reason, now);
        jdbc.update("update devicesec.isolation set released_at = ?, released_by = ?, release_note = ? where endpoint_id = ? and released_at is null",
                EndpointRepository.ts(now), actor, note == null ? null : note.substring(0, Math.min(note.length(), 500)), endpointId);
        audit.record("DEVICE_ENDPOINT_RELEASE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, reason);
        events.record("ENDPOINT_RELEASED", DeviceCodes.Severity.MEDIUM, endpointId, null, actor, reason, Map.of());
        afterCommit(endpointId, instances, EndpointStatus.ACTIVE, "RELEASED");
        return new StatusChange(endpointId, EndpointStatus.ACTIVE, reason, null, now, null, null);
    }

    @Transactional
    public StatusChange revokeEndpoint(String actor, String endpointId, String reasonCode, String note) {
        settings.requireEnabled();
        return doRevoke(actor, endpointId, reason(reasonCode, "MANUAL_REVOCATION"));
    }

    private StatusChange doRevoke(String actor, String endpointId, String reasonCode) {
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        Instant now = settings.now();
        if (ep.status() == EndpointStatus.REVOKED) {
            return new StatusChange(endpointId, EndpointStatus.REVOKED, ep.statusReason(), null, ep.revokedAt(), null, null);
        }
        repo.setStatus(endpointId, EndpointStatus.REVOKED, reasonCode, now);
        repo.revokeOpenEnrolmentTokens(endpointId, now);
        Set<String> instances = repo.instances(endpointId);
        audit.recordAs(actor, "", "DEVICE_ENDPOINT_REVOKE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, reasonCode);
        events.record("ENDPOINT_REVOKED", DeviceCodes.Severity.HIGH, endpointId, null, actor, reasonCode, Map.of());
        afterCommit(endpointId, instances, EndpointStatus.REVOKED, reasonCode);
        return new StatusChange(endpointId, EndpointStatus.REVOKED, reasonCode, null, now, null, null);
    }

    // ------------------------------------------------------------------ DeviceEndpointProvisioning (E32)

    @Override
    @Transactional
    public EndpointIssue provision(String actor, ProvisionRequest r) {
        settings.requireEnabled();
        if (r == null || r.deviceInstanceId() == null || !INSTANCE_ID.matcher(r.deviceInstanceId()).matches()) {
            throw ApiException.badRequest("validation_failed", "Device instance id required");
        }
        if (r.existingEndpointId() != null && !r.existingEndpointId().isBlank()) {
            Endpoint ep = repo.findForUpdate(r.existingEndpointId()).orElseThrow(() -> ApiException.notFound("Endpoint"));
            if (ep.status() == EndpointStatus.REVOKED) {
                throw ApiException.conflict("endpoint_revoked", "The endpoint is revoked");
            }
            repo.addInstance(ep.endpointId(), r.deviceInstanceId(), actor, settings.now());
            audit.recordAs(actor, "", "DEVICE_ENDPOINT_CREATE", "DEVICE_ENDPOINT", ep.endpointId(), AuditService.Outcome.SUCCESS, "INSTANCE_ADDED");
            return new EndpointIssue(ep.endpointId(), ep.status(), null, null);
        }
        Issued issued = create(actor, new CreateCommand(r.kind(), r.siteCode(), r.zone(), r.tenantCode(), List.of(r.deviceInstanceId()), List.of()));
        return new EndpointIssue(issued.endpointId(), issued.status(), issued.enrolmentToken(), issued.enrolmentExpiresAt());
    }

    @Override
    @Transactional
    public void revoke(String actor, String endpointId, String deviceInstanceId, String reasonCode) {
        settings.requireEnabled();
        Endpoint ep = repo.findForUpdate(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        String reason = reason(reasonCode, "DECOMMISSIONED");
        if (deviceInstanceId != null) {
            Set<String> instances = repo.instances(endpointId);
            if (instances.contains(deviceInstanceId) && instances.size() > 1) {
                repo.removeInstance(endpointId, deviceInstanceId);
                audit.recordAs(actor, "", "DEVICE_ENDPOINT_REVOKE", "DEVICE_ENDPOINT", endpointId, AuditService.Outcome.SUCCESS, reason + " instance");
                events.record("ENDPOINT_REVOKED", DeviceCodes.Severity.MEDIUM, endpointId, deviceInstanceId, actor, reason, Map.of());
                afterCommit(endpointId, Set.of(deviceInstanceId), EndpointStatus.REVOKED, reason);
                return;
            }
        }
        doRevoke(actor, ep.endpointId(), reason);
    }

    @Override
    @Transactional(readOnly = true)
    public EndpointState state(String endpointId) {
        Endpoint ep = repo.find(endpointId).orElseThrow(() -> ApiException.notFound("Endpoint"));
        Instant since = switch (ep.status()) {
            case PENDING_ENROLMENT -> ep.createdAt();
            case ACTIVE -> ep.releasedAt() != null ? ep.releasedAt() : ep.enrolledAt() != null ? ep.enrolledAt() : ep.createdAt();
            case ISOLATED -> ep.isolatedAt();
            case REVOKED -> ep.revokedAt();
        };
        return new EndpointState(ep.endpointId(), ep.status(), since, ep.recertificationRequired(), ep.kind(), ep.zone());
    }

    // ------------------------------------------------------------------ helpers

    private void afterCommit(String endpointId, Set<String> instances, EndpointStatus status, String reason) {
        Set<String> copy = Set.copyOf(new LinkedHashSet<>(instances));
        Runnable call = () -> {
            for (DeviceEndpointStatusListener l : spis.endpointListeners()) {
                try {
                    l.endpointStatusChanged(endpointId, copy, status, reason);
                } catch (RuntimeException e) {
                    log.warn("Endpoint status listener {} failed: {}", l.getClass().getSimpleName(), e.getClass().getSimpleName());
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    call.run();
                }
            });
        } else {
            call.run();
        }
    }

    private static String reason(String reasonCode, String fallback) {
        if (reasonCode == null || reasonCode.isBlank()) {
            return fallback;
        }
        if (!REASON.matcher(reasonCode).matches()) {
            throw ApiException.badRequest("validation_failed", "Reason code must be upper snake case");
        }
        return reasonCode;
    }

    EndpointView view(Endpoint e) {
        CertificateView cert = e.certSha256() == null ? null
                : new CertificateView(e.certSha256(), e.certSubject(), e.certIssuer(), e.certSerial(), e.certNotBefore(), e.certNotAfter());
        Long days = e.certNotAfter() == null ? null : Duration.between(settings.now(), e.certNotAfter()).toDays();
        Instant enrolmentExpires = e.status() == EndpointStatus.PENDING_ENROLMENT ? repo.openEnrolmentExpiry(e.endpointId()).orElse(null) : null;
        return new EndpointView(e.endpointId(), e.kind(), e.siteCode(), e.zone(), e.tenantCode(), e.status(), e.statusReason(), cert, days,
                e.tokenIssuedAt(), e.tokenExpiresAt(), enrolmentExpires, repo.instances(e.endpointId()), e.allowedCidrs(), e.hostname(), e.platformOs(),
                e.platformOsVersion(), e.agentVersion(), e.hardeningBaseline(), e.hardeningStatus(), e.attested(), e.recertificationRequired(),
                e.createdBy(), e.createdAt(), e.enrolledAt(), e.firstSeenAt(), e.lastSeenAt(), e.isolatedAt(), e.releasedAt(), e.revokedAt());
    }

    /** Counts per status for the console summary. */
    @Transactional(readOnly = true)
    public Map<String, Integer> statusCounts() {
        Map<String, Integer> m = new HashMap<>();
        jdbc.query("select status, count(*) as n from devicesec.endpoint group by status", rs -> {
            m.put(rs.getString("status"), rs.getInt("n"));
        });
        return m;
    }
}
