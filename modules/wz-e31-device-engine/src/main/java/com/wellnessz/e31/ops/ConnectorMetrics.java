package com.wellnessz.e31.ops;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Micrometer metrics per connector (E31-HLR-11, E31-F11) with PHI-free labels only: {@code connector} (device instance id),
 * {@code option}, {@code site}, {@code stage}, {@code outcome}. Exported through the application's OpenTelemetry /
 * Prometheus registry.
 */
@Component("e31ConnectorMetrics")
public class ConnectorMetrics {

    private final MeterRegistry registry;

    public ConnectorMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable(SimpleMeterRegistry::new);
    }

    static String tag(String v) {
        return v == null || v.isBlank() ? "none" : v;
    }

    public void message(String connector, String option, String site, String stage, String outcome) {
        Counter.builder("wz.device.messages").description("Device messages per connector, stage and outcome")
                .tags("connector", tag(connector), "option", tag(option), "site", tag(site), "stage", tag(stage), "outcome", tag(outcome))
                .register(registry).increment();
    }

    public void latency(String connector, String option, String site, String stage, Duration d) {
        Timer.builder("wz.device.latency").description("Device-to-stage latency per connector").publishPercentiles(0.5, 0.95)
                .tags("connector", tag(connector), "option", tag(option), "site", tag(site), "stage", tag(stage))
                .register(registry).record(d);
    }

    public void unmatched(String site) {
        Counter.builder("wz.device.unmatched").tags("site", tag(site)).register(registry).increment();
    }

    public void deadLetter(String connector, String stage, String code) {
        Counter.builder("wz.device.dead_letters").tags("connector", tag(connector), "stage", tag(stage), "outcome", tag(code))
                .register(registry).increment();
    }

    public MeterRegistry registry() {
        return registry;
    }
}
