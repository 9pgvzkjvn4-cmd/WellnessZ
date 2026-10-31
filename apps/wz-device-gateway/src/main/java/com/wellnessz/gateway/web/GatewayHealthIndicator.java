package com.wellnessz.gateway.web;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import com.wellnessz.gateway.queue.QueueStats;

/**
 * {@code gateway} health: DOWN when not enrolled / not ready or the queue is quarantined; OUT_OF_SERVICE at ≥ 90 %
 * queue capacity; UP otherwise (being offline is a normal, supported state of an edge node).
 */
@Component("gateway")
public class GatewayHealthIndicator implements HealthIndicator {

    private final GatewayStatus status;

    public GatewayHealthIndicator(GatewayStatus status) {
        this.status = status;
    }

    @Override
    public Health health() {
        GatewayStatus.Snapshot s = status.snapshot();
        QueueStats q = s.queue();
        Health.Builder b;
        if (!s.enrolled() || !s.ready() || q.quarantined()) {
            b = Health.down();
        } else if (q.capacityPct() >= 90) {
            b = Health.outOfService();
        } else {
            b = Health.up();
        }
        return b.withDetail("coreOnline", s.coreOnline())
                .withDetail("queueDepth", q.depth())
                .withDetail("queueCapacityPct", q.capacityPct())
                .withDetail("quarantined", q.quarantined())
                .build();
    }
}
