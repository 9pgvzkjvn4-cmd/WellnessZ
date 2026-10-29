package com.wellnessz.casekernel.intake;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.CaseService;
import com.wellnessz.casekernel.model.CaseCategory;
import com.wellnessz.casekernel.model.CaseSource;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Subscribes to EHS incident events (primary trigger feed) and applies incident-to-case rules. */
@Component
public class IncidentListener {

    public static final String EVENT_TYPE = "ehs.incident.received.v1";
    private static final Logger log = LoggerFactory.getLogger(IncidentListener.class);

    private final List<IncidentToCaseRule> rules;
    private final CaseService cases;
    private final IdempotentConsumer consumer;
    private final JsonMapper json;
    private final ModuleRegistry modules;

    public IncidentListener(List<IncidentToCaseRule> rules, CaseService cases, IdempotentConsumer consumer, JsonMapper json,
                            ModuleRegistry modules) {
        this.modules = modules;
        this.rules = rules;
        this.cases = cases;
        this.consumer = consumer;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    @EventListener(condition = "#envelope.eventType() == '" + EVENT_TYPE + "'")
    public void onIncident(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E01")) {
            log.info("E01 disabled – incident {} kept in intake only", envelope.aggregateId());
            return;
        }
        consumer.handleOnce("case-kernel.incident-to-case", envelope, () -> {
            IncidentToCaseRule.IncidentPayload incident = json.readValue(envelope.payload(), IncidentToCaseRule.IncidentPayload.class);
            Optional<CaseCategory> category = rules.stream().map(r -> r.evaluate(incident)).flatMap(Optional::stream).findFirst();
            if (category.isPresent()) {
                cases.openFromTrigger(incident.employeeId(), incident.employeeName(), incident.orgUnitCode(), category.get(),
                        CaseSource.EHS_INCIDENT, incident.incidentId(), null);
            } else {
                log.info("Incident {} did not meet case-opening criteria", incident.incidentId());
            }
        });
    }
}
