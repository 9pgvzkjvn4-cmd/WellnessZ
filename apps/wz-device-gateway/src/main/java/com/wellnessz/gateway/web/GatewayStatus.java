package com.wellnessz.gateway.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.wellnessz.gateway.GatewayRuntime;
import com.wellnessz.gateway.config.GatewayProperties;
import com.wellnessz.gateway.connector.ConnectorRegistry;
import com.wellnessz.gateway.connector.ConnectorStatus;
import com.wellnessz.gateway.core.ClockSkew;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.identity.EndpointIdentity;
import com.wellnessz.gateway.identity.IdentityStore;
import com.wellnessz.gateway.ops.ConfigService;
import com.wellnessz.gateway.ops.HardeningCheck;
import com.wellnessz.gateway.ops.HeartbeatService;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.QueueStats;

/** Assembles the PHI-free status snapshot shown on the local status page and used by the health indicator. */
@Component
public class GatewayStatus {

    /** Snapshot (no PHI, no secrets). */
    public record Snapshot(String role, String endpointId, boolean enrolled, boolean ready, String configVersion,
                           boolean coreOnline, Instant lastForwardAt, String lastForwardError, Instant nextAttemptAt,
                           Instant lastHeartbeatAt, String lastHeartbeatError, long clockOffsetMillis,
                           QueueStats queue, List<ConnectorStatus> connectors, Map<String, Object> hardening) {
    }

    private final GatewayProperties props;
    private final IdentityStore identities;
    private final GatewayRuntime runtime;
    private final ConfigService config;
    private final Forwarder forwarder;
    private final HeartbeatService heartbeat;
    private final ClockSkew skew;
    private final EncryptedJournal journal;
    private final ConnectorRegistry connectors;
    private final HardeningCheck hardening;

    public GatewayStatus(GatewayProperties props, IdentityStore identities, GatewayRuntime runtime,
                         ConfigService config, Forwarder forwarder, HeartbeatService heartbeat, ClockSkew skew,
                         EncryptedJournal journal, ConnectorRegistry connectors, HardeningCheck hardening) {
        this.props = props;
        this.identities = identities;
        this.runtime = runtime;
        this.config = config;
        this.forwarder = forwarder;
        this.heartbeat = heartbeat;
        this.skew = skew;
        this.journal = journal;
        this.connectors = connectors;
        this.hardening = hardening;
    }

    public Snapshot snapshot() {
        EndpointIdentity id = identities.current();
        return new Snapshot(props.role().name(), id == null ? null : id.endpointId(), id != null, runtime.ready(),
                config.configVersion(), forwarder.online(), forwarder.lastSuccessAt(), forwarder.lastError(),
                forwarder.nextAttemptAt(), heartbeat.lastOkAt(), heartbeat.lastError(), skew.offsetMillis(),
                journal.stats(), connectors.all().stream().map(c -> c.status()).toList(), hardening.run());
    }
}
