package com.wellnessz.integration.outbox;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.wellnessz.common.events.IntegrationEnvelope;

/** Dispatches to in-process {@code @EventListener}s – the module-to-module bus inside the modular monolith. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class InProcessEventPublisher implements EventPublisher {

    public static final String DESTINATION = "internal";
    private final ApplicationEventPublisher publisher;

    public InProcessEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean supports(String destination) {
        return DESTINATION.equals(destination);
    }

    @Override
    public void publish(String destination, IntegrationEnvelope envelope) {
        publisher.publishEvent(envelope);
    }
}
