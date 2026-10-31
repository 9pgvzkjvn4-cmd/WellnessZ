package com.wellnessz.e34.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes.EndpointKind;

class EndpointPolicyTest {

    @Test
    void resourceIsTheFirstSegmentAfterTheIngestPrefix() {
        assertThat(EndpointPolicy.resourceOf("/api/v1/device-ingest/hl7v2")).isEqualTo("hl7v2");
        assertThat(EndpointPolicy.resourceOf("/api/v1/device-ingest/commands/abc/result")).isEqualTo("commands");
        assertThat(EndpointPolicy.resourceOf("/api/v1/device-ingest/kiosk/sessions")).isEqualTo("kiosk");
        assertThat(EndpointPolicy.resourceOf("/api/v1/device-ingest/FHIR?x=1")).isEqualTo("fhir");
        assertThat(EndpointPolicy.resourceOf("/api/v1/me")).isEmpty();
        assertThat(EndpointPolicy.resourceOf(null)).isEmpty();
    }

    @Test
    void pathsAreAllowedPerEndpointType() {
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.GATEWAY, "/api/v1/device-ingest/hl7v2")).isTrue();
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.GATEWAY, "/api/v1/device-ingest/heartbeat")).isTrue();
        // a serial Device Host agent never sends FHIR or BLE (UNEXPECTED_PROTOCOL)
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.DEVICE_HOST_AGENT, "/api/v1/device-ingest/serial")).isTrue();
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.DEVICE_HOST_AGENT, "/api/v1/device-ingest/fhir")).isFalse();
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.DEVICE_HOST_AGENT, "/api/v1/device-ingest/ble")).isFalse();
        // kiosks only use the kiosk API and BLE
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.KIOSK, "/api/v1/device-ingest/kiosk/sessions")).isTrue();
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.KIOSK, "/api/v1/device-ingest/hl7v2")).isFalse();
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.KIOSK, "/api/v1/device-ingest/secrets/devsec:x")).isFalse();
        // vendor clouds never call the ingest API (HMAC webhooks only)
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.VENDOR_CLOUD, "/api/v1/device-ingest/config")).isFalse();
        // certificate renewal is open to every enrolled kind except vendor clouds
        for (EndpointKind k : List.of(EndpointKind.GATEWAY, EndpointKind.DEVICE_HOST_AGENT, EndpointKind.MOBILE_APP, EndpointKind.KIOSK,
                EndpointKind.DATA_MANAGER, EndpointKind.DEVICE)) {
            assertThat(EndpointPolicy.pathAllowed(k, "/api/v1/device-ingest/certificate-renewal")).as(k.name()).isTrue();
        }
        assertThat(EndpointPolicy.pathAllowed(EndpointKind.GATEWAY, "/api/v1/device-ingest/unknown")).isFalse();
    }

    @Test
    void zoneRules() {
        assertThat(EndpointPolicy.deviceInDeviceVlan(EndpointKind.DEVICE, "DEVICE_VLAN")).isTrue();
        assertThat(EndpointPolicy.deviceInDeviceVlan(EndpointKind.DEVICE, "CLINIC_LAN")).isFalse();
        assertThat(EndpointPolicy.deviceInDeviceVlan(EndpointKind.GATEWAY, "DEVICE_VLAN")).isFalse();
        assertThat(EndpointPolicy.requiresGatewayZone(EndpointKind.GATEWAY)).isTrue();
        assertThat(EndpointPolicy.requiresGatewayZone(EndpointKind.DEVICE_HOST_AGENT)).isTrue();
        assertThat(EndpointPolicy.requiresGatewayZone(EndpointKind.MOBILE_APP)).isFalse();
        assertThat(EndpointPolicy.defaultZone(EndpointKind.KIOSK)).isEqualTo("CLINIC_LAN");
        assertThat(EndpointPolicy.kindCode(EndpointKind.DEVICE_HOST_AGENT)).isEqualTo("AGT");
    }

    @Test
    void cidrMatchingIpv4Ipv6AndMapped() {
        assertThat(EndpointPolicy.inCidr("10.20.30.40", "10.0.0.0/8")).isTrue();
        assertThat(EndpointPolicy.inCidr("11.0.0.1", "10.0.0.0/8")).isFalse();
        assertThat(EndpointPolicy.inCidr("192.168.1.130", "192.168.1.128/25")).isTrue();
        assertThat(EndpointPolicy.inCidr("192.168.1.127", "192.168.1.128/25")).isFalse();
        assertThat(EndpointPolicy.inCidr("172.31.255.255", "172.16.0.0/12")).isTrue();
        assertThat(EndpointPolicy.inCidr("172.32.0.1", "172.16.0.0/12")).isFalse();
        assertThat(EndpointPolicy.inCidr("::1", "::1/128")).isTrue();
        assertThat(EndpointPolicy.inCidr("2001:db8::5", "2001:db8::/32")).isTrue();
        assertThat(EndpointPolicy.inCidr("::ffff:10.1.2.3", "10.0.0.0/8")).isTrue();
        assertThat(EndpointPolicy.inCidr("10.1.2.3", "2001:db8::/32")).isFalse();
        assertThat(EndpointPolicy.inCidr("0.0.0.1", "0.0.0.0/0")).isTrue();
    }

    @Test
    void malformedInputNeverMatchesAndHostNamesAreNotResolved() {
        assertThat(EndpointPolicy.inCidr("localhost", "127.0.0.0/8")).isFalse();
        assertThat(EndpointPolicy.inCidr("999.1.1.1", "0.0.0.0/0")).isFalse();
        assertThat(EndpointPolicy.inCidr("10.0.0.1", "10.0.0.0/33")).isFalse();
        assertThat(EndpointPolicy.inCidr("10.0.0.1", "garbage")).isFalse();
        assertThat(EndpointPolicy.inAny(null, List.of("0.0.0.0/0"))).isFalse();
        assertThat(EndpointPolicy.inAny("10.0.0.1", List.of())).isFalse();
        assertThat(EndpointPolicy.validCidr("10.0.0.0/8")).isTrue();
        assertThat(EndpointPolicy.validCidr("10.0.0.0")).isFalse();
        assertThat(EndpointPolicy.validCidr("example.org/8")).isFalse();
        assertThat(EndpointPolicy.validSite("LHR")).isTrue();
        assertThat(EndpointPolicy.validSite("lhr")).isFalse();
    }
}
