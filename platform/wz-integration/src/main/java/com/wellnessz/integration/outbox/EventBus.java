package com.wellnessz.integration.outbox;

import java.time.Clock;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.events.DomainEvent;
import com.wellnessz.common.web.CorrelationIdFilter;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes domain events via the transactional outbox: the event is persisted atomically with the business
 * change (no dual-write problem) and delivered at-least-once by {@link OutboxRelay}.
 */
@Component
public class EventBus {

    private final OutboxRepository repository;
    private final JsonMapper json;
    private final Clock clock;
    private final OutboundPayloadGuard guard;

    public EventBus(OutboxRepository repository, JsonMapper json, Clock clock, OutboundPayloadGuard guard) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.guard = guard;
    }

    /** Publish to in-process subscribers. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(DomainEvent event) {
        return publish(event, InProcessEventPublisher.DESTINATION);
    }

    /** Publish to a named destination (enterprise system). Outbound payloads are checked by the disclosure guard. */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent publish(DomainEvent event, String destination) {
        String payload = json.writeValueAsString(event);
        guard.check(destination, payload);
        return repository.save(new OutboxEvent(event.eventType(), event.aggregateType(), event.aggregateId(), destination,
                payload, CorrelationIdFilter.current(), clock.instant()));
    }
}
