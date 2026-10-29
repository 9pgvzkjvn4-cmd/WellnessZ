package com.wellnessz.integration.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.events.IntegrationEnvelope;

/**
 * Exactly-once <i>effect</i> on top of at-least-once delivery: a consumer handles a given message id only once.
 * The handler runs in its own transaction together with the de-duplication marker, so either both commit or
 * neither does (a failure leaves the message eligible for redelivery).
 */
@Component
public class IdempotentConsumer {

    private final JdbcTemplate jdbc;

    public IdempotentConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean handleOnce(String consumer, IntegrationEnvelope envelope, Runnable handler) {
        int inserted = jdbc.update("""
                insert into platform.processed_message(consumer, message_id, processed_at)
                values (?, ?, now()) on conflict do nothing
                """, consumer, envelope.messageId());
        if (inserted == 0) {
            return false; // duplicate delivery
        }
        handler.run();
        return true;
    }
}
