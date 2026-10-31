package com.wellnessz.gateway.connector;

import java.time.Instant;

/** PHI-free connector state for the heartbeat ({@code devices[]}) and the local status page. */
public record ConnectorStatus(String id, String type, String deviceInstanceId, State state, Instant lastSeenAt,
                              long received, long errors, String detail) {

    /** Heartbeat device states (contract §7). */
    public enum State { CONNECTED, IDLE, ERROR, PAIRING }
}
