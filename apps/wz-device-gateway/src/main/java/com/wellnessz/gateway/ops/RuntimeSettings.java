package com.wellnessz.gateway.ops;

import java.time.Duration;

import com.wellnessz.gateway.config.GatewayProperties;

/** Values that the core's {@code GatewayConfig} may override at run time (applied atomically by {@link ConfigService}). */
public class RuntimeSettings {

    private volatile int heartbeatSeconds;
    private volatile int retentionHours;

    public RuntimeSettings(GatewayProperties props) {
        this.heartbeatSeconds = props.heartbeat().intervalSeconds();
        this.retentionHours = props.queue().retentionHours();
    }

    public Duration heartbeatInterval() {
        return Duration.ofSeconds(Math.max(5, heartbeatSeconds));
    }

    public Duration retention() {
        return Duration.ofHours(Math.max(48, retentionHours));
    }

    void apply(int heartbeatSeconds, int retentionHours) {
        if (heartbeatSeconds > 0) {
            this.heartbeatSeconds = heartbeatSeconds;
        }
        if (retentionHours > 0) {
            this.retentionHours = Math.max(48, retentionHours);
        }
    }
}
