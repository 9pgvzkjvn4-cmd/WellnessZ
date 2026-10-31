package com.wellnessz.gateway.core;

import java.time.Instant;

/**
 * Edge-versus-core clock offset estimated from the heartbeat round trip (NTP-style midpoint):
 * {@code offset = edgeMidpoint − serverTime}. Positive = the edge clock is ahead. Reported in the heartbeat as
 * {@code ntpOffsetMillis}, so the engine can correct {@code capturedAt} (contract §7, §16.2).
 */
public class ClockSkew {

    private volatile Long offsetMillis;
    private volatile Instant measuredAt;

    public void record(Instant sentAt, Instant receivedAt, Instant serverTime) {
        long mid = sentAt.toEpochMilli() + (receivedAt.toEpochMilli() - sentAt.toEpochMilli()) / 2;
        this.offsetMillis = mid - serverTime.toEpochMilli();
        this.measuredAt = receivedAt;
    }

    /** Last measured offset in milliseconds, 0 before the first measurement. */
    public long offsetMillis() {
        Long v = offsetMillis;
        return v == null ? 0 : v;
    }

    public boolean measured() {
        return offsetMillis != null;
    }

    public Instant measuredAt() {
        return measuredAt;
    }
}
