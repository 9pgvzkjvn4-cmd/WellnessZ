package com.wellnessz.gateway.queue;

import java.time.Instant;

/** PHI-free queue figures for the heartbeat ({@code queue{depth, oldestCapturedAt, bytes, capacityPct}}), metrics and status page. */
public record QueueStats(int depth, Instant oldestCapturedAt, long bytes, double capacityPct, long nextSequence,
                         long deadLetters, long corruptRecords, boolean quarantined) {
}
