package com.wellnessz.e34.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;
import com.wellnessz.casekernel.device.DeviceCodes.EndpointStatus;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator.Credentials;
import com.wellnessz.casekernel.device.DeviceEndpointAuthenticator.Result;
import com.wellnessz.e34.identity.EndpointRepository.Endpoint;
import com.wellnessz.e34.support.E34Settings;
import com.wellnessz.e34.support.TokenHasher;
import com.wellnessz.e34.zoning.ZoneService;

/** The authentication rules in contract order, each failure code, rotation grace and fail-closed behaviour. */
class EndpointAuthenticatorServiceTest {

    static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    static final String TOKEN = "token-value-0123456789";
    static final String THUMB = "ab".repeat(32);
    static final String PATH = "/api/v1/device-ingest/hl7v2";

    final TokenHasher hasher = new TokenHasher("unit-test-pepper-0123456789".getBytes());
    EndpointRepository repo;
    ZoneService zones;
    TrafficMonitor traffic;
    E34Settings settings;
    EndpointAuthenticatorService auth;

    @BeforeEach
    void setUp() {
        repo = mock(EndpointRepository.class);
        zones = mock(ZoneService.class);
        traffic = mock(TrafficMonitor.class);
        settings = mock(E34Settings.class);
        when(settings.enabled()).thenReturn(true);
        when(settings.now()).thenReturn(NOW);
        when(zones.ranges(anyString(), anyString())).thenReturn(List.of("10.0.0.0/8"));
        when(traffic.record(anyString(), anyString(), any())).thenReturn(new TrafficMonitor.Verdict(false, false, 1, 600));
        when(repo.instances(anyString())).thenReturn(Set.of("DEV-1"));
        auth = new EndpointAuthenticatorService(repo, hasher, zones, traffic, settings);
    }

    Endpoint endpoint(EndpointKind kind, String zone, EndpointStatus status, String tokenHash, Instant tokenExpires, String prevHash,
                      Instant prevUntil, String thumb, Instant notAfter, List<String> cidrs) {
        return new Endpoint("EP-LHR-GW-01", kind, "LHR", zone, "GROUP", status, null, thumb, "CN=x", "CN=ca", "01", NOW.minusSeconds(86400),
                notAfter, tokenHash, NOW.minusSeconds(3600), tokenExpires, prevHash, prevUntil, cidrs, null, null, null, null, null, null, false,
                false, "t", NOW, NOW, NOW, NOW, null, null, null);
    }

    Endpoint active() {
        return endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400), null, null, THUMB,
                NOW.plusSeconds(86400 * 30L), List.of());
    }

    Result call(Endpoint ep, String token, String thumb, String address, String path) {
        when(repo.find("EP-LHR-GW-01")).thenReturn(Optional.ofNullable(ep));
        return auth.authenticate(new Credentials("EP-LHR-GW-01", thumb, token, address, "POST", path));
    }

    @Test
    void successCarriesThePrincipalWithItsInstances() {
        Result r = call(active(), TOKEN, THUMB, "10.1.2.3", PATH);
        assertThat(r.authenticated()).isTrue();
        assertThat(r.principal().endpointId()).isEqualTo("EP-LHR-GW-01");
        assertThat(r.principal().deviceInstanceIds()).containsExactly("DEV-1");
        assertThat(r.principal().zone()).isEqualTo("GATEWAY_ZONE");
    }

    @Test
    void unknownEndpoint() {
        assertThat(call(null, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("unknown_endpoint");
    }

    @Test
    void statusIsCheckedBeforeTheToken() {
        for (var s : List.of(EndpointStatus.PENDING_ENROLMENT, EndpointStatus.ISOLATED, EndpointStatus.REVOKED)) {
            Endpoint ep = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", s, hasher.hash(TOKEN), NOW.plusSeconds(60), null, null, THUMB,
                    NOW.plusSeconds(600), List.of());
            assertThat(call(ep, "wrong", THUMB, "10.1.2.3", PATH).failureCode())
                    .isEqualTo(switch (s) {
                        case PENDING_ENROLMENT -> "endpoint_pending";
                        case ISOLATED -> "endpoint_isolated";
                        default -> "endpoint_revoked";
                    });
        }
        verify(traffic, never()).record(anyString(), anyString(), any());
    }

    @Test
    void badAndExpiredTokens() {
        assertThat(call(active(), "not-the-token", THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("bad_token");
        Endpoint expired = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW, null, null, THUMB,
                NOW.plusSeconds(600), List.of());
        assertThat(call(expired, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("token_expired");
    }

    @Test
    void previousTokenIsAcceptedOnlyWithinTheGracePeriod() {
        Endpoint inGrace = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash("new"), NOW.plusSeconds(86400),
                hasher.hash(TOKEN), NOW.plusSeconds(300), THUMB, NOW.plusSeconds(600), List.of());
        assertThat(call(inGrace, TOKEN, THUMB, "10.1.2.3", PATH).authenticated()).isTrue();
        Endpoint afterGrace = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash("new"), NOW.plusSeconds(86400),
                hasher.hash(TOKEN), NOW.minusSeconds(1), THUMB, NOW.plusSeconds(600), List.of());
        assertThat(call(afterGrace, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("bad_token");
    }

    @Test
    void thumbprintMustMatchTheBoundValidCertificate() {
        assertThat(call(active(), TOKEN, "cd".repeat(32), "10.1.2.3", PATH).failureCode()).isEqualTo("certificate_mismatch");
        assertThat(call(active(), TOKEN, null, "10.1.2.3", PATH).failureCode()).isEqualTo("mtls_required");
        Endpoint expiredCert = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400),
                null, null, THUMB, NOW.minusSeconds(1), List.of());
        assertThat(call(expiredCert, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("certificate_mismatch");
        Endpoint noCert = endpoint(EndpointKind.GATEWAY, "GATEWAY_ZONE", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400),
                null, null, null, null, List.of());
        assertThat(call(noCert, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("certificate_mismatch");
    }

    @Test
    void zoneAndCidrPolicy() {
        // gateway outside its site's GATEWAY_ZONE ranges
        assertThat(call(active(), TOKEN, THUMB, "203.0.113.5", PATH).failureCode()).isEqualTo("zone_violation");
        // no ranges configured at all -> fail closed
        when(zones.ranges(anyString(), anyString())).thenReturn(List.of());
        assertThat(call(active(), TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("zone_violation");
        // per-endpoint CIDR policy applies to any kind
        Endpoint tablet = endpoint(EndpointKind.MOBILE_APP, "CLINIC_LAN", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400), null,
                null, THUMB, NOW.plusSeconds(600), List.of("192.168.10.0/24"));
        assertThat(call(tablet, TOKEN, THUMB, "192.168.11.1", "/api/v1/device-ingest/ble").failureCode()).isEqualTo("zone_violation");
        assertThat(call(tablet, TOKEN, THUMB, "192.168.10.7", "/api/v1/device-ingest/ble").authenticated()).isTrue();
        // a device in the device VLAN never reaches the core directly
        Endpoint device = endpoint(EndpointKind.DEVICE, "DEVICE_VLAN", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400), null, null,
                THUMB, NOW.plusSeconds(600), List.of());
        assertThat(call(device, TOKEN, THUMB, "10.1.2.3", "/api/v1/device-ingest/fhir").failureCode()).isEqualTo("zone_violation");
    }

    @Test
    void pathNotAllowedForTheEndpointType() {
        Endpoint kiosk = endpoint(EndpointKind.KIOSK, "CLINIC_LAN", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400), null, null,
                THUMB, NOW.plusSeconds(600), List.of());
        assertThat(call(kiosk, TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("endpoint_forbidden");
        Endpoint vendor = endpoint(EndpointKind.VENDOR_CLOUD, "VENDOR_CLOUD", EndpointStatus.ACTIVE, hasher.hash(TOKEN), NOW.plusSeconds(86400),
                null, null, THUMB, NOW.plusSeconds(600), List.of());
        assertThat(call(vendor, TOKEN, THUMB, "10.1.2.3", "/api/v1/device-ingest/config").failureCode()).isEqualTo("endpoint_forbidden");
        // endpoint_forbidden (403 at the filter) is only ever an authorisation verdict: without valid credentials the
        // authentication failure is reported first (401)
        assertThat(call(vendor, "wrong-token", THUMB, "10.1.2.3", "/api/v1/device-ingest/config").failureCode()).isEqualTo("bad_token");
        assertThat(call(kiosk, "wrong-token", THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("bad_token");
    }

    @Test
    void throttledEndpointsAreRefused() {
        when(traffic.record(anyString(), anyString(), any())).thenReturn(new TrafficMonitor.Verdict(true, false, 900, 600));
        assertThat(call(active(), TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("rate_limited");
    }

    @Test
    void failsClosedWhenDisabledOrOnErrors() {
        when(settings.enabled()).thenReturn(false);
        assertThat(call(active(), TOKEN, THUMB, "10.1.2.3", PATH).failureCode()).isEqualTo("endpoint_security_unavailable");
        when(settings.enabled()).thenReturn(true);
        when(repo.find("EP-LHR-GW-01")).thenThrow(new IllegalStateException("db down"));
        assertThat(auth.authenticate(new Credentials("EP-LHR-GW-01", THUMB, TOKEN, "10.1.2.3", "POST", PATH)).failureCode())
                .isEqualTo("endpoint_security_unavailable");
        assertThat(auth.authenticate(new Credentials("EP-LHR-GW-01", THUMB, null, "10.1.2.3", "POST", PATH)).failureCode())
                .isEqualTo("credentials_missing");
    }
}
