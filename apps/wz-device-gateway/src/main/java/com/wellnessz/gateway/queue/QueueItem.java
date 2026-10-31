package com.wellnessz.gateway.queue;

import java.time.Instant;
import java.util.Map;

/**
 * One captured message (contract §16.2): {@code sequence} is monotonic and persisted, {@code idempotencyKey} is
 * {@code "<endpointId>:<sequence>"} or the SHA-256 of the content for file-like sources, so a replay after a crash or a
 * lost receipt is recognised by the core as a duplicate.
 */
public record QueueItem(long sequence, String idempotencyKey, ItemKind kind, String deviceInstanceId,
                        Instant capturedAt, String contentType, Map<String, String> headers, byte[] body) {

    public QueueItem {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }
}
