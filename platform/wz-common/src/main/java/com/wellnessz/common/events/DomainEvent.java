package com.wellnessz.common.events;

import java.time.Instant;

/**
 * A fact that happened inside a bounded context. Events are written to the transactional outbox and
 * relayed to subscribers; payloads must respect the data classification of the producing module.
 */
public interface DomainEvent {

    /** Stable, versioned type name, e.g. {@code ehs.incident.received.v1}. */
    String eventType();

    String aggregateType();

    String aggregateId();

    default Instant occurredAt() {
        return Instant.now();
    }
}
