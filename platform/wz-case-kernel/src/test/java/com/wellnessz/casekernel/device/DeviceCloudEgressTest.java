package com.wellnessz.casekernel.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wellnessz.masterdata.ConfigService;

/**
 * {@code device.cloud.allowed-hosts} has one source – the configuration entry – read by both the E32 wizard and the E31 poller;
 * the Spring property only seeds a missing or blank entry.
 */
class DeviceCloudEgressTest {

    /** A ConfigService backed by a map (the real one needs JPA). */
    static ConfigService config(Map<String, String> store) {
        ConfigService c = mock(ConfigService.class);
        when(c.get(anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.<String>getArgument(0))));
        when(c.put(anyString(), any(), any(), any())).thenAnswer(i -> {
            store.put(i.getArgument(0), i.getArgument(1));
            return null;
        });
        return c;
    }

    @Test
    void readsTheConfigurationEntryAndIgnoresThePropertyOnceTheEntryIsSet() {
        Map<String, String> store = new HashMap<>(Map.of(DeviceCloudEgress.KEY, " api.vendor.example , .cloud.example ,,api.vendor.example"));
        ConfigService config = config(store);
        DeviceCloudEgress egress = new DeviceCloudEgress(config, "other.example");
        egress.seedFromProperty();
        verify(config, never()).put(anyString(), any(), any(), any());
        assertThat(egress.allowedHosts()).containsExactly("api.vendor.example", ".cloud.example");
        // an administrator's change is visible at once (no cache) – the wizard and the poller read the same value
        store.put(DeviceCloudEgress.KEY, "new.vendor.example");
        assertThat(egress.allowedHosts()).containsExactly("new.vendor.example");
    }

    @Test
    void thePropertySeedsABlankOrMissingEntry() {
        Map<String, String> blank = new HashMap<>(Map.of(DeviceCloudEgress.KEY, ""));
        DeviceCloudEgress seeded = new DeviceCloudEgress(config(blank), " a.example, .b.example ");
        assertThat(seeded.allowedHosts()).isEmpty();
        seeded.seedFromProperty();
        assertThat(blank.get(DeviceCloudEgress.KEY)).isEqualTo("a.example,.b.example");
        assertThat(seeded.allowedHosts()).containsExactly("a.example", ".b.example");

        Map<String, String> missing = new HashMap<>();
        DeviceCloudEgress created = new DeviceCloudEgress(config(missing), "c.example");
        created.seedFromProperty();
        assertThat(created.allowedHosts()).isEqualTo(List.of("c.example"));
    }

    @Test
    void noPropertyAndNoEntryMeansNoOutboundCall() {
        ConfigService config = config(new HashMap<>());
        DeviceCloudEgress egress = new DeviceCloudEgress(config, null);
        egress.seedFromProperty();
        verify(config, never()).put(anyString(), any(), any(), any());
        assertThat(egress.allowedHosts()).isEmpty();
    }
}
