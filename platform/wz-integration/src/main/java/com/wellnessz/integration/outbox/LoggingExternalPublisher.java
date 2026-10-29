package com.wellnessz.integration.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.wellnessz.common.events.IntegrationEnvelope;

/**
 * Placeholder for external destinations in local / dev environments. Logs metadata only (never the payload).
 * Replace with a broker adapter (Kafka / Service Bus / Pub/Sub) per environment.
 */
@Component
@ConditionalOnProperty(name = "wellnessz.integration.external-publisher", havingValue = "logging", matchIfMissing = true)
public class LoggingExternalPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingExternalPublisher.class);

    @Override
    public boolean supports(String destination) {
        return !InProcessEventPublisher.DESTINATION.equals(destination);
    }

    @Override
    public void publish(String destination, IntegrationEnvelope envelope) {
        log.info("External delivery destination={} type={} messageId={}", destination, envelope.eventType(), envelope.messageId());
    }
}
