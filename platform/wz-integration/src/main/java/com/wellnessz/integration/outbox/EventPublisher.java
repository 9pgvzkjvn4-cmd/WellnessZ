package com.wellnessz.integration.outbox;

import com.wellnessz.common.events.IntegrationEnvelope;

/**
 * Delivery SPI behind the outbox relay. The default implementation dispatches in-process; adapters for
 * Apache Kafka, Azure Service Bus or Google Pub/Sub implement this interface and declare which destinations
 * they handle (e.g. {@code crew-rostering}, {@code erp-finance}).
 */
public interface EventPublisher {

    boolean supports(String destination);

    /** Must be idempotent from the receiver's perspective: the envelope's messageId is the deduplication key. */
    void publish(String destination, IntegrationEnvelope envelope) throws Exception;
}
