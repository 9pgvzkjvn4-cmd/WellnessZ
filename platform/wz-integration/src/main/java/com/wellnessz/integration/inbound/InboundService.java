package com.wellnessz.integration.inbound;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.integration.outbox.EventBus;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUserProvider;

/** Persists inbound messages and announces them on the event bus atomically. */
@Service
public class InboundService {

    private final InboundMessageRepository repository;
    private final EventBus bus;
    private final AuditService audit;
    private final CurrentUserProvider users;
    private final Clock clock;

    public InboundService(InboundMessageRepository repository, EventBus bus, AuditService audit, CurrentUserProvider users, Clock clock) {
        this.repository = repository;
        this.bus = bus;
        this.audit = audit;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public InboundMessage accept(String source, String format, String messageType, String externalId, String payload) {
        InboundMessage msg = repository.save(new InboundMessage(source, format, messageType, externalId, payload,
                users.actorOrSystem(), clock.instant()));
        bus.publish(new InboundMessageReceived(msg.getId().toString(), source, format, messageType));
        audit.record("INBOUND_" + format, "INBOUND_MESSAGE", msg.getId().toString(), AuditService.Outcome.SUCCESS, source);
        return msg;
    }
}
