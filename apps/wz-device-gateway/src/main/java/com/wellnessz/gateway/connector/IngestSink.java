package com.wellnessz.gateway.connector;

import java.util.Map;
import java.util.Optional;

import com.wellnessz.gateway.core.CoreResponse;
import com.wellnessz.gateway.forward.Forwarder;
import com.wellnessz.gateway.queue.EncryptedJournal;
import com.wellnessz.gateway.queue.ItemKind;
import com.wellnessz.gateway.queue.QueueItem;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Entry point for every connector: the message is first written (fsync-ed, encrypted) to the queue, then optionally
 * forwarded at once. Nothing is acknowledged to a device before the queue write has returned.
 */
public class IngestSink {

    private final EncryptedJournal journal;
    private final Forwarder forwarder;
    private final MeterRegistry metrics;

    public IngestSink(EncryptedJournal journal, Forwarder forwarder, MeterRegistry metrics) {
        this.journal = journal;
        this.forwarder = forwarder;
        this.metrics = metrics;
    }

    /** Durable capture. Throws {@link com.wellnessz.gateway.queue.QueueFullException} when the queue is full. */
    public QueueItem capture(String connectorType, ItemKind kind, String deviceInstanceId, String contentType,
                             Map<String, String> headers, byte[] body, String idempotencyKey) {
        QueueItem item = journal.append(kind, deviceInstanceId, contentType, headers, body, idempotencyKey);
        metrics.counter("wz.gateway.messages.received", "connector", connectorType, "kind", kind.name()).increment();
        return item;
    }

    /**
     * Durable capture for listeners that relay the core's acknowledgement: the item is held back from the drain loop
     * until {@link #forwardNow} has been called (which must follow at once).
     */
    public QueueItem captureForImmediate(String connectorType, ItemKind kind, String deviceInstanceId,
                                         String contentType, Map<String, String> headers, byte[] body) {
        QueueItem item = journal.append(kind, deviceInstanceId, contentType, headers, body, null, true);
        metrics.counter("wz.gateway.messages.received", "connector", connectorType, "kind", kind.name()).increment();
        return item;
    }

    /**
     * Synchronous forward attempt; empty means "stored, will be forwarded later". A sink without forwarder
     * (capture-only, used by tooling and tests) always defers.
     */
    public Optional<CoreResponse> forwardNow(QueueItem item) {
        if (forwarder == null) {
            journal.releaseHold(item.sequence());
            return Optional.empty();
        }
        return forwarder.forwardNow(item);
    }
}
