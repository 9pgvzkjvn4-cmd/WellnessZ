package com.wellnessz.e09.cism;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e09.rules.CismRules;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes to the EHS incident feed: serious incidents (severity HIGH / CRITICAL or a configured trigger class – fatality,
 * assault, fume event, aircraft accident, serious injury, emergency landing) open a CISM event with the affected people from the
 * duty rosters (E09-BL-010). Exactly once per message and idempotent on the incident id.
 */
@Component
public class CismIncidentListener {

    public static final String EVENT_TYPE = "ehs.incident.received.v1";
    private static final Logger log = LoggerFactory.getLogger(CismIncidentListener.class);

    private final CismService cism;
    private final IdempotentConsumer consumer;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public CismIncidentListener(CismService cism, IdempotentConsumer consumer, ModuleRegistry modules, JsonMapper json) {
        this.cism = cism;
        this.consumer = consumer;
        this.modules = modules;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @EventListener(condition = "#envelope.eventType() == '" + EVENT_TYPE + "'")
    public void onIncident(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E09")) {
            return;
        }
        try {
            consumer.handleOnce("e09.cism", envelope, () -> {
                IncidentFacts f = json.readValue(envelope.payload(), IncidentFacts.class);
                if (CismRules.triggers(f.severity(), f.incidentClass(), cism.triggerClasses()) && cism.fromIncident(f) != null) {
                    log.info("E09 CISM event created from a serious incident");
                }
            });
        } catch (RuntimeException e) {
            // never block the other incident subscribers (E01 intake); the message stays unprocessed for E09 and is retried
            log.warn("E09 CISM handling of an incident failed: {}", e.getClass().getSimpleName());
        }
    }
}
