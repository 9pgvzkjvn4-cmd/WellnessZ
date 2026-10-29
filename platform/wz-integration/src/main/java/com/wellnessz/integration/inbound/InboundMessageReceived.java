package com.wellnessz.integration.inbound;

import com.wellnessz.common.events.DomainEvent;

/** Emitted for every accepted inbound message; only metadata – consumers load the payload by id under ABAC. */
public record InboundMessageReceived(String inboundId, String source, String format, String messageType) implements DomainEvent {

    public static final String TYPE = "integration.inbound.received.v1";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "INBOUND_MESSAGE";
    }

    @Override
    public String aggregateId() {
        return inboundId;
    }
}
