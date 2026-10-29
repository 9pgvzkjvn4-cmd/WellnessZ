package com.wellnessz.integration.outbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.common.events.IntegrationEnvelope;

/**
 * Relays outbox rows to publishers with retry and exponential back-off; rows exceeding the attempt limit move
 * to DEAD (dead-letter) and can be replayed by an operator after the root cause is fixed.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final List<EventPublisher> publishers;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(OutboxRepository repository, List<EventPublisher> publishers, TransactionTemplate tx, Clock clock,
                       @Value("${wellnessz.integration.outbox.batch-size:50}") int batchSize,
                       @Value("${wellnessz.integration.outbox.max-attempts:10}") int maxAttempts) {
        this.repository = repository;
        this.publishers = publishers;
        this.tx = tx;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${wellnessz.integration.outbox.poll-interval:PT2S}")
    public void relay() {
        Integer processed;
        do {
            processed = tx.execute(status -> relayBatch());
        } while (processed != null && processed == batchSize);
    }

    /** Package-visible for tests. Must run inside a transaction (row locks held until commit). */
    int relayBatch() {
        List<OutboxEvent> due = repository.claimDue(clock.instant(), batchSize);
        for (OutboxEvent e : due) {
            IntegrationEnvelope envelope = new IntegrationEnvelope(e.getId(), e.getEventType(), e.getAggregateType(),
                    e.getAggregateId(), e.getPayload(),
                    e.getCorrelationId() == null ? Map.of() : Map.of("correlationId", e.getCorrelationId()), e.getCreatedAt());
            try {
                EventPublisher publisher = publishers.stream().filter(p -> p.supports(e.getDestination())).findFirst()
                        .orElseThrow(() -> new IllegalStateException("No publisher for destination " + e.getDestination()));
                publisher.publish(e.getDestination(), envelope);
                e.markPublished(clock.instant());
            } catch (Exception ex) {
                e.markFailed(ex.getClass().getSimpleName() + ": " + ex.getMessage(), clock.instant(), maxAttempts);
                log.warn("Outbox delivery failed id={} type={} attempts={} status={}", e.getId(), e.getEventType(),
                        e.getAttempts(), e.getStatus());
            }
        }
        return due.size();
    }
}
