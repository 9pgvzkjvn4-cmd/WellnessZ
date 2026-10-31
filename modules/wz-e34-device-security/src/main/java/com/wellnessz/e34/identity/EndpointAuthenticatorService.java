package com.wellnessz.e34.identity;

import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator;
import com.wellnessz.casekernel.device.DeviceEndpointPrincipal;
import com.wellnessz.e34.identity.EndpointRepository.Endpoint;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.e34.zoning.ZoneService;

/**
 * Fail-closed {@link DeviceEndpointAuthenticator} (E34-HLR-01, contract §15.3). Rules in contract order: endpoint exists →
 * status ACTIVE → token hash matches (constant time, rotation grace) and is unexpired → thumbprint equals the bound certificate
 * and the certificate is within validity → zone / CIDR policy for the remote address → path allowed for the endpoint type →
 * not throttled. Any internal error answers {@code endpoint_security_unavailable}. No cache: revocation and isolation take
 * effect on the next request (SLA 5 minutes). The failure event is published by the kernel filter, not here.
 */
@Service
public class EndpointAuthenticatorService implements DeviceEndpointAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(EndpointAuthenticatorService.class);

    private final EndpointRepository repo;
    private final TokenHasher hasher;
    private final ZoneService zones;
    private final TrafficMonitor traffic;
    private final E34Settings settings;

    public EndpointAuthenticatorService(EndpointRepository repo, TokenHasher hasher, ZoneService zones, TrafficMonitor traffic, E34Settings settings) {
        this.repo = repo;
        this.hasher = hasher;
        this.zones = zones;
        this.traffic = traffic;
        this.settings = settings;
    }

    @Override
    public Result authenticate(Credentials c) {
        try {
            if (c == null || c.endpointId() == null || c.token() == null) {
                return Result.failure("credentials_missing");
            }
            if (!settings.enabled()) {
                return Result.failure("endpoint_security_unavailable");
            }
            Optional<Endpoint> found = repo.find(c.endpointId());
            if (found.isEmpty()) {
                return Result.failure("unknown_endpoint");
            }
            Endpoint ep = found.get();
            Instant now = settings.now();
            String verdict = check(ep, c, now);
            if (verdict != null) {
                return Result.failure(verdict);
            }
            TrafficMonitor.Verdict v = traffic.record(ep.endpointId(), ep.siteCode(), c.path());
            if (v.throttled()) {
                return Result.failure("rate_limited");
            }
            return Result.success(new DeviceEndpointPrincipal(ep.endpointId(), ep.kind(), ep.siteCode(), ep.zone(), ep.tenantCode(),
                    repo.instances(ep.endpointId()), c.certificateSha256()));
        } catch (RuntimeException e) {
            log.warn("Endpoint authentication failed closed: {}", e.getClass().getSimpleName());
            return Result.failure("endpoint_security_unavailable");
        }
    }

    /** The first failing rule's code, or {@code null} when every rule passes. */
    String check(Endpoint ep, Credentials c, Instant now) {
        switch (ep.status()) {
            case PENDING_ENROLMENT -> {
                return "endpoint_pending";
            }
            case ISOLATED -> {
                return "endpoint_isolated";
            }
            case REVOKED -> {
                return "endpoint_revoked";
            }
            case ACTIVE -> {
                // continue
            }
        }
        if (ep.kind() == EndpointKind.VENDOR_CLOUD) {
            return "endpoint_forbidden"; // vendor clouds use HMAC webhooks / OAuth2 pulls, never the ingest API
        }
        String presented = hasher.hash(c.token());
        boolean current = TokenHasher.constantTimeEquals(presented, ep.tokenHash());
        boolean previous = TokenHasher.constantTimeEquals(presented, ep.prevTokenHash());
        if (current) {
            if (ep.tokenExpiresAt() == null || !now.isBefore(ep.tokenExpiresAt())) {
                return "token_expired";
            }
        } else if (!previous || ep.prevTokenValidUntil() == null || !now.isBefore(ep.prevTokenValidUntil())) {
            return "bad_token";
        }
        if (c.certificateSha256() == null) {
            return "mtls_required";
        }
        if (ep.certSha256() == null || !TokenHasher.constantTimeEquals(c.certificateSha256(), ep.certSha256())) {
            return "certificate_mismatch";
        }
        if (ep.certNotAfter() != null && !now.isBefore(ep.certNotAfter()) || ep.certNotBefore() != null && now.isBefore(ep.certNotBefore())) {
            return "certificate_mismatch";
        }
        if (EndpointPolicy.deviceInDeviceVlan(ep.kind(), ep.zone())) {
            return "zone_violation";
        }
        if (!ep.allowedCidrs().isEmpty() && !EndpointPolicy.inAny(c.remoteAddress(), ep.allowedCidrs())) {
            return "zone_violation";
        }
        if (EndpointPolicy.requiresGatewayZone(ep.kind())
                && !EndpointPolicy.inAny(c.remoteAddress(), zones.ranges(ep.siteCode(), "GATEWAY_ZONE"))) {
            return "zone_violation";
        }
        if (!EndpointPolicy.pathAllowed(ep.kind(), c.path())) {
            return "endpoint_forbidden";
        }
        return null;
    }
}
