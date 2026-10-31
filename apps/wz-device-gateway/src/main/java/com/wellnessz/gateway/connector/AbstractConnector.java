package com.wellnessz.gateway.connector;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Common counters and state for connectors. */
public abstract class AbstractConnector implements Connector {

    protected final String id;
    protected final String deviceInstanceId;
    protected final IngestSink sink;
    protected final Clock clock;
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile Instant lastSeenAt;
    private volatile ConnectorStatus.State state = ConnectorStatus.State.IDLE;
    private volatile String detail;
    protected volatile boolean running;

    protected AbstractConnector(String id, String deviceInstanceId, IngestSink sink, Clock clock) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("connector id must match [A-Za-z0-9._-]{1,64}");
        }
        this.id = id;
        this.deviceInstanceId = deviceInstanceId;
        this.sink = sink;
        this.clock = clock;
    }

    @Override
    public String id() {
        return id;
    }

    protected void seen() {
        received.incrementAndGet();
        lastSeenAt = clock.instant();
        state = ConnectorStatus.State.CONNECTED;
        detail = null;
    }

    protected void error(String reason) {
        errors.incrementAndGet();
        state = ConnectorStatus.State.ERROR;
        detail = reason;
    }

    protected void state(ConnectorStatus.State s, String reason) {
        state = s;
        detail = reason;
    }

    @Override
    public ConnectorStatus status() {
        return new ConnectorStatus(id, type(), deviceInstanceId, state, lastSeenAt, received.get(), errors.get(), detail);
    }
}
