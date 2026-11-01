package com.wellnessz.e31.support;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.device.DeviceCloudEgress;
import com.wellnessz.masterdata.ConfigService;

/**
 * Engine thresholds from the platform configuration (V21 / V22 config keys, contract section 17), cached for 30 seconds.
 */
@Component("e31EngineSettings")
public class EngineSettings {

    public static final String ENGINE_VERSION = "1.0.0";
    public static final String DEFAULT_TENANT = "GROUP";

    private final ConfigService config;
    private final Clock clock;
    private final boolean requireRegisteredDevices;
    private final DeviceCloudEgress cloudEgress;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(String value, Instant at) {
    }

    public EngineSettings(ConfigService config, Clock clock, DeviceCloudEgress cloudEgress,
                          @Value("${wellnessz.record.require-registered-devices:false}") boolean requireRegisteredDevices) {
        this.config = config;
        this.clock = clock;
        this.cloudEgress = cloudEgress;
        this.requireRegisteredDevices = requireRegisteredDevices;
    }

    String raw(String key, String fallback) {
        Instant now = clock.instant();
        Cached c = cache.get(key);
        if (c != null && c.at().plusSeconds(30).isAfter(now)) {
            return c.value() == null ? fallback : c.value();
        }
        String v = config.get(key).orElse(null);
        cache.put(key, new Cached(v, now));
        return v == null ? fallback : v;
    }

    BigDecimal decimal(String key, String fallback) {
        try {
            return new BigDecimal(raw(key, fallback).strip());
        } catch (NumberFormatException e) {
            return new BigDecimal(fallback);
        }
    }

    long number(String key, long fallback) {
        try {
            return Long.parseLong(raw(key, String.valueOf(fallback)).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public BigDecimal autoFileThreshold() {
        return decimal("device.association.auto-file-threshold", "0.95");
    }

    public BigDecimal suggestThreshold() {
        return decimal("device.association.suggest-threshold", "0.60");
    }

    public long sessionWindowSeconds() {
        return number("device.association.session-window-seconds", 900);
    }

    public long maxSkewSeconds() {
        return number("device.clock.max-skew-seconds", 120);
    }

    public long unmatchedSlaHours() {
        return number("device.unmatched.resolution-sla-hours", 8);
    }

    public BigDecimal ocrMinConfidence() {
        return decimal("device.ocr.min-confidence", "0.90");
    }

    public long maxPayloadBytes() {
        return number("device.ingest.max-payload-bytes", 52_428_800L);
    }

    public int maxAttempts() {
        return (int) number("device.dlq.max-attempts", 5);
    }

    public boolean inline() {
        return !"false".equalsIgnoreCase(raw("device.pipeline.inline", "true").strip());
    }

    /** P6 egress allow-list – the same configuration entry the E32 wizard checks ({@link DeviceCloudEgress}, not cached here). */
    public List<String> cloudAllowedHosts() {
        return cloudEgress.allowedHosts();
    }

    public boolean requireRegisteredDevices() {
        return requireRegisteredDevices;
    }
}
