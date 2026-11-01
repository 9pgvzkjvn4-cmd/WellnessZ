package com.wellnessz.casekernel.device;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.wellnessz.masterdata.ConfigService;

/**
 * The one source of the P6 vendor-cloud egress allow-list ({@code device.cloud.allowed-hosts}, contract §13.3 P6 / release notes
 * §7): the platform configuration entry (E18, seeded by V22). The E32 wizard (step 4 semantic check) and the E31 poller (before
 * every outbound call) both read it here, so an administrator's change applies to both at once.
 * <p>
 * The Spring property {@code device.cloud.allowed-hosts} (environment {@code DEVICE_CLOUD_ALLOWED_HOSTS}) is only the
 * <b>default seed</b>: at start-up it is written into the configuration entry when the entry is missing or blank. A non-blank
 * entry is never overwritten. To switch egress off again, clear both the entry and the property.
 * <p>
 * Values are comma-separated exact host names or leading-dot domain suffixes ({@code .vendor.example}); empty = no outbound call.
 */
@Component
public class DeviceCloudEgress {

    public static final String KEY = "device.cloud.allowed-hosts";
    static final String DESCRIPTION = "Hosts the engine may call for P6 vendor-cloud polling (comma-separated; empty = no outbound call)";
    static final String SEED_ACTOR = "property:" + KEY;
    private static final Logger log = LoggerFactory.getLogger(DeviceCloudEgress.class);

    private final ConfigService config;
    private final String propertyDefault;

    public DeviceCloudEgress(ConfigService config, @Value("${" + KEY + ":}") String propertyDefault) {
        this.config = config;
        this.propertyDefault = propertyDefault == null ? "" : propertyDefault.strip();
    }

    /** The allow-list as configured now (no cache: the wizard and the poller see a change immediately). */
    public List<String> allowedHosts() {
        return parse(config.get(KEY).orElse(""));
    }

    /** Seeds the configuration entry from the property when the entry is missing or blank (before the poller's first tick). */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void seedFromProperty() {
        if (propertyDefault.isEmpty()) {
            return;
        }
        Optional<String> current = config.get(KEY);
        if (current.isPresent() && !current.get().isBlank()) {
            return;
        }
        String normalised = String.join(",", parse(propertyDefault));
        config.put(KEY, normalised, DESCRIPTION, SEED_ACTOR);
        log.info("Seeded configuration entry {} from the application property ({} host(s))", KEY, parse(normalised).size());
    }

    static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).distinct().toList();
    }
}
