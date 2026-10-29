package com.wellnessz.e03.results;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e03.orders.Hl7Messages;
import com.wellnessz.integration.hl7.Hl7v2Service;
import com.wellnessz.integration.idempotency.IdempotentConsumer;
import com.wellnessz.integration.inbound.InboundMessage;
import com.wellnessz.integration.inbound.InboundMessageReceived;
import com.wellnessz.integration.inbound.InboundMessageRepository;

import ca.uhn.hl7v2.HL7Exception;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns HL7 v2 ORU^R01 messages accepted by {@code /integration/hl7v2} into results (E03-BL-010), exactly once per
 * inbound message. OBX segments are grouped per patient / placer order number and processed like the JSON LIS feed.
 */
@Component
public class OruResultListener {

    private static final Logger log = LoggerFactory.getLogger(OruResultListener.class);

    private final InboundMessageRepository inbound;
    private final Hl7v2Service hl7;
    private final Hl7Messages messages;
    private final ResultService results;
    private final IdempotentConsumer consumer;
    private final JsonMapper json;

    public OruResultListener(InboundMessageRepository inbound, Hl7v2Service hl7, Hl7Messages messages, ResultService results,
                             IdempotentConsumer consumer, JsonMapper json) {
        this.inbound = inbound;
        this.hl7 = hl7;
        this.messages = messages;
        this.results = results;
        this.consumer = consumer;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @EventListener(condition = "#envelope.eventType() == 'integration.inbound.received.v1'")
    public void onInbound(IntegrationEnvelope envelope) {
        InboundMessageReceived event = json.readValue(envelope.payload(), InboundMessageReceived.class);
        if (!"HL7V2".equals(event.format()) || event.messageType() == null || !event.messageType().startsWith("ORU^R01")) {
            return;
        }
        consumer.handleOnce("e03.oru-results", envelope, () -> process(UUID.fromString(event.inboundId()), event.source()));
    }

    void process(UUID inboundId, String source) {
        InboundMessage msg = inbound.findById(inboundId).orElse(null);
        if (msg == null) {
            log.warn("Inbound message {} not found", inboundId);
            return;
        }
        List<Hl7Messages.Observation> observations;
        try {
            observations = messages.observations(hl7.parse(msg.getPayload()).message());
        } catch (HL7Exception e) {
            log.warn("ORU^R01 {} could not be processed: {}", inboundId, e.getClass().getSimpleName());
            return;
        }
        Map<String, List<Hl7Messages.Observation>> groups = new LinkedHashMap<>();
        for (Hl7Messages.Observation o : observations) {
            groups.computeIfAbsent(o.employeeId() + "|" + (o.placerOrderNumber() == null ? "" : o.placerOrderNumber()),
                    k -> new ArrayList<>()).add(o);
        }
        for (List<Hl7Messages.Observation> group : groups.values()) {
            Hl7Messages.Observation first = group.get(0);
            List<ResultService.LisObservation> obs = group.stream().map(o -> new ResultService.LisObservation(o.code(), o.value(), o.unit(),
                    o.referenceRange(), o.interpretation(), o.status(), o.effectiveAt())).toList();
            String placer = first.placerOrderNumber() == null || first.placerOrderNumber().isBlank() ? null : first.placerOrderNumber();
            try {
                results.ingestIsolated(new ResultService.LisResults(placer, first.employeeId(), obs), source);
            } catch (ApiException e) {
                // unknown employee / order mismatch: the raw message stays in platform.inbound_message for replay
                log.warn("ORU^R01 {} group rejected: {}", inboundId, e.code());
            }
        }
    }
}
