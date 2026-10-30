package com.wellnessz.e10.trigger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes to the EHS incident feed ({@code ehs.incident.received.v1}) in-process: qualifying incidents of employees in a
 * testing pool create a POST_INCIDENT requirement with alcohol / drug windows (E10-BL-003). Idempotent on the message and on
 * the incident id.
 */
@Component("e10IncidentTestListener")
public class IncidentTestListener {

    public static final String EVENT_TYPE = "ehs.incident.received.v1";
    private static final Logger log = LoggerFactory.getLogger(IncidentTestListener.class);

    private final TriggerService triggers;
    private final IdempotentConsumer consumer;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public IncidentTestListener(TriggerService triggers, IdempotentConsumer consumer, ModuleRegistry modules, JsonMapper json) {
        this.triggers = triggers;
        this.consumer = consumer;
        this.modules = modules;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @EventListener(condition = "#envelope.eventType() == '" + EVENT_TYPE + "'")
    public void onIncident(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E10")) {
            return;
        }
        try {
            consumer.handleOnce("e10.post-incident", envelope,
                    () -> triggers.onIncident(json.readValue(envelope.payload(), TriggerService.IncidentFacts.class)));
        } catch (RuntimeException e) {
            // never break the other incident subscribers (E01 intake) – the incident stays visible in reconciliation
            log.warn("E10 post-incident evaluation failed for {}: {}", envelope.aggregateId(), e.getClass().getSimpleName());
        }
    }
}
