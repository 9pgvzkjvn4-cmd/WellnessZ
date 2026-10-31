package com.wellnessz.gateway.ops;

import java.time.Clock;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.wellnessz.gateway.connector.ConnectorRegistry;
import com.wellnessz.gateway.core.ClockSkew;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.QueueStats;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Gauges with PHI-free labels only (contract §0 observability): queue depth / bytes / capacity / oldest age / dead
 * letters, online state, clock offset and per-connector received / error counts. Counters for received, forwarded and
 * failed messages are registered by the sink and the forwarder.
 */
@Component
public class GatewayMetrics {

    public GatewayMetrics(MeterRegistry registry, EncryptedJournal journal, Forwarder forwarder, ClockSkew skew,
                          ConnectorRegistry connectors, Clock clock) {
        Gauge.builder("wz.gateway.queue.depth", journal, j -> j.stats().depth()).register(registry);
        Gauge.builder("wz.gateway.queue.bytes", journal, j -> j.stats().bytes()).baseUnit("bytes").register(registry);
        Gauge.builder("wz.gateway.queue.capacity.pct", journal, j -> j.stats().capacityPct()).register(registry);
        Gauge.builder("wz.gateway.queue.dead.letters", journal, j -> j.stats().deadLetters()).register(registry);
        Gauge.builder("wz.gateway.queue.oldest.age", journal, j -> {
            QueueStats s = j.stats();
            return s.oldestCapturedAt() == null ? 0 : Duration.between(s.oldestCapturedAt(), clock.instant()).toSeconds();
        }).baseUnit("seconds").register(registry);
        Gauge.builder("wz.gateway.queue.quarantined", journal, j -> j.quarantined() ? 1 : 0).register(registry);
        Gauge.builder("wz.gateway.core.online", forwarder, f -> f.online() ? 1 : 0).register(registry);
        Gauge.builder("wz.gateway.clock.offset", skew, ClockSkew::offsetMillis).baseUnit("milliseconds")
                .register(registry);
        connectors.all().forEach(c -> {
            Gauge.builder("wz.gateway.connector.received", c, x -> x.status().received())
                    .tag("connector", c.id()).tag("option", c.type()).register(registry);
            Gauge.builder("wz.gateway.connector.errors", c, x -> x.status().errors())
                    .tag("connector", c.id()).tag("option", c.type()).register(registry);
        });
    }
}
