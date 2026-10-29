package com.wellnessz.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Transport envelope delivered to in-process subscribers (and serialised to external brokers).
 * Consumers deserialise {@link #payload()} into their own DTO, keeping modules decoupled.
 */
public record IntegrationEnvelope(
        UUID messageId,
        String eventType,
        String aggregateType,
        String aggregateId,
        String payload,
        Map<String, String> headers,
        Instant occurredAt) {
}
