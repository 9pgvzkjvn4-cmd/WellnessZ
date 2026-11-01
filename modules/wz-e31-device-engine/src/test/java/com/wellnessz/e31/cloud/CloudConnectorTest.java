package com.wellnessz.e31.cloud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e31.TestSupport;
import com.wellnessz.e31.driver.DriverPackage;

/**
 * P6 connector settings (E31-BL-008): the published E32 option parameters (nested oauth2 / poll / consentMapping groups) win over
 * the driver package's poll defaults, {@code PARAMETER:} references resolve in both layouts, the interval never drops below
 * 60 s, the egress allow-list matches exact hosts and leading-dot suffixes only, and failures back off exponentially up to an hour.
 */
class CloudConnectorTest {

    static DeviceCatalogueLookup.DeviceInstance instance(Map<String, Object> params) {
        return new DeviceCatalogueLookup.DeviceInstance("DEV-WCP", "MD-121", DeviceCodes.Family.WEARABLE, "Vendor", "Cloud", null, null, null, null,
                DeviceCodes.Option.P6_CLOUD_API, "PUBLISHED", "LHR", null, null, "wear.cloud.daily-poll", "1.0.0", Set.of(DeviceCodes.Category.WEARABLE),
                params, List.of("VENDOR_ID_MATCH"), false, List.of(), "UTC", "GROUP", 1);
    }

    DriverPackage pkg() {
        return TestSupport.bundled().get("wear.cloud.daily-poll", "1.0.0").orElseThrow();
    }

    @Test
    void e32OptionParametersWinOverTheDriverDefaults() {
        CloudConnector c = CloudConnector.of(instance(Map.of("apiBaseUrl", "https://api.vendor.example/", "residencyRegion", "UK",
                "oauth2", Map.of("tokenUrl", "https://auth.vendor.example/token", "clientId", "wz", "clientSecretRef", "devsec:abc", "scopes", List.of("a", "b")),
                "poll", Map.of("enabled", true, "intervalSeconds", 30, "rateLimitPerMinute", 12, "path", "/v2/days"),
                "consentMapping", Map.of("purpose", "WEARABLE_DATA"))), pkg());
        assertThat(c.enabled()).isTrue();
        assertThat(c.intervalSeconds()).isEqualTo(60); // never below the schema minimum
        assertThat(c.rateLimitPerMinute()).isEqualTo(12);
        assertThat(c.scopes()).containsExactly("a", "b");
        assertThat(c.clientSecretRef()).isEqualTo("devsec:abc");
        assertThat(c.consentPurpose()).isEqualTo("WEARABLE_DATA");
        assertThat(c.subjectPointer()).isEqualTo("/subject/employeeNumber");
        assertThat(c.pageUri("2026-10-30T23:59:59Z").toString())
                .isEqualTo("https://api.vendor.example/v2/days?limit=100&since=2026-10-30T23%3A59%3A59Z");
    }

    @Test
    void driverDefaultsAndFlatParameterReferencesStillResolve() {
        CloudConnector c = CloudConnector.of(instance(Map.of("apiBaseUrl", "https://api.vendor.example", "tokenUrl", "https://auth.vendor.example/t",
                "clientId", "wz", "clientSecretRef", "devsec:flat")), pkg());
        assertThat(c.enabled()).isTrue(); // the package enables polling
        assertThat(c.intervalSeconds()).isEqualTo(900);
        assertThat(c.path()).isEqualTo("/v1/daily-summaries");
        assertThat(c.tokenUrl()).isEqualTo("https://auth.vendor.example/t");
        assertThat(c.clientSecretRef()).isEqualTo("devsec:flat");
        assertThat(c.scopes()).containsExactly("summaries.read");
        assertThat(c.consentPurpose()).isEqualTo("WEARABLE_DATA"); // from the package's consentPurpose
        assertThat(c.pageUri(null).toString()).isEqualTo("https://api.vendor.example/v1/daily-summaries?limit=100");
        assertThat(CloudConnector.parameter(Map.of("webhook", Map.of("webhookSecretRef", "devsec:w")), "webhookSecretRef")).isEqualTo("devsec:w");
    }

    @Test
    void egressAllowListAndBackoff() {
        assertThat(CloudPollService.hostAllowed("https://api.vendor.example/x", List.of("api.vendor.example"))).isTrue();
        assertThat(CloudPollService.hostAllowed("https://eu.api.vendor.example/x", List.of(".vendor.example"))).isTrue();
        assertThat(CloudPollService.hostAllowed("https://vendor.example.evil.test/x", List.of(".vendor.example"))).isFalse();
        assertThat(CloudPollService.hostAllowed("https://evilvendor.example/x", List.of(".vendor.example"))).isFalse();
        assertThat(CloudPollService.hostAllowed("https://api.vendor.example/x", List.of())).isFalse();
        assertThat(CloudPollService.backoff(300, 0)).isEqualTo(300);
        assertThat(CloudPollService.backoff(300, 1)).isEqualTo(600);
        assertThat(CloudPollService.backoff(300, 3)).isEqualTo(2400);
        assertThat(CloudPollService.backoff(300, 10)).isEqualTo(3600);
    }
}
