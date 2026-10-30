package com.wellnessz.e11.exposure;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e01.rules.IncidentFacts;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes to the EHS incident feed ({@code ehs.incident.received.v1}): incidents whose exposure type is a needle-stick /
 * body fluid, chemical, fume or heat exposure start the matching exposure pathway automatically (idempotent on the incident id).
 */
@Component
public class ExposureIncidentListener {

    public static final String EVENT_TYPE = "ehs.incident.received.v1";
    private static final Logger log = LoggerFactory.getLogger(ExposureIncidentListener.class);

    private final ExposurePathwayService exposures;
    private final IdempotentConsumer consumer;
    private final ModuleRegistry modules;
    private final JsonMapper json;

    public ExposureIncidentListener(ExposurePathwayService exposures, IdempotentConsumer consumer, ModuleRegistry modules, JsonMapper json) {
        this.exposures = exposures;
        this.consumer = consumer;
        this.modules = modules;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @EventListener(condition = "#envelope.eventType() == '" + EVENT_TYPE + "'")
    public void onIncident(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E11")) {
            return;
        }
        try {
            consumer.handleOnce("e11.exposure-pathways", envelope, () -> handle(json.readValue(envelope.payload(), IncidentFacts.class)));
        } catch (RuntimeException e) {
            // never break the other incident subscribers (case creation) – the exposure can still be opened manually
            log.warn("Exposure pathway for incident {} not started: {}", envelope.aggregateId(), e.getClass().getSimpleName());
        }
    }

    void handle(IncidentFacts facts) {
        if (facts.incidentId() == null) {
            return;
        }
        UUID incidentId;
        try {
            incidentId = UUID.fromString(facts.incidentId());
        } catch (IllegalArgumentException e) {
            return;
        }
        exposures.fromIncident(new ExposurePathwayService.IncidentInput(incidentId, facts.externalRef(), facts.employeeId(), facts.exposureType(),
                facts.occurredAt())).ifPresent(id -> log.info("Exposure pathway {} started from incident {}", id, incidentId));
    }
}
