package com.wellnessz.e01.rules;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.module.ModuleRegistry;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.integration.idempotency.IdempotentConsumer;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Subscribes to the EHS incident feed (primary trigger) and applies the configurable rule table:
 * AUTO_CREATE opens a pre-filled case, SUGGEST parks the incident for nurse review, IGNORE / NO_MATCH are logged
 * so that reconciliation shows every incident's fate (E01-F01).
 */
@Component
public class IncidentIntakeListener {

    public static final String EVENT_TYPE = "ehs.incident.received.v1";
    private static final Logger log = LoggerFactory.getLogger(IncidentIntakeListener.class);

    private final RuleEngine engine;
    private final IncidentEvaluationRepository evaluations;
    private final IncidentCaseFactory factory;
    private final IdempotentConsumer consumer;
    private final ModuleRegistry modules;
    private final JsonMapper json;
    private final Clock clock;

    public IncidentIntakeListener(RuleEngine engine, IncidentEvaluationRepository evaluations, IncidentCaseFactory factory,
                                  IdempotentConsumer consumer, ModuleRegistry modules, JsonMapper json, Clock clock) {
        this.engine = engine;
        this.evaluations = evaluations;
        this.factory = factory;
        this.consumer = consumer;
        this.modules = modules;
        this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.clock = clock;
    }

    @EventListener(condition = "#envelope.eventType() == '" + EVENT_TYPE + "'")
    public void onIncident(IntegrationEnvelope envelope) {
        if (!modules.isEnabled("E01")) {
            log.info("E01 disabled – incident {} kept in intake only", envelope.aggregateId());
            return;
        }
        consumer.handleOnce("e01.incident-rules", envelope, () -> evaluate(json.readValue(envelope.payload(), IncidentFacts.class)));
    }

    void evaluate(IncidentFacts facts) {
        UUID incidentId = UUID.fromString(facts.incidentId());
        Optional<IntakeRule> rule = engine.match(facts);
        IncidentEvaluation.Outcome outcome = rule.map(r -> switch (r.getAction()) {
            case AUTO_CREATE -> IncidentEvaluation.Outcome.AUTO_CREATED;
            case SUGGEST -> IncidentEvaluation.Outcome.SUGGESTED;
            case IGNORE -> IncidentEvaluation.Outcome.IGNORED;
        }).orElse(IncidentEvaluation.Outcome.NO_MATCH);
        boolean identified = facts.employeeId() != null && !facts.employeeId().isBlank();
        if (outcome == IncidentEvaluation.Outcome.AUTO_CREATED && !identified) {
            outcome = IncidentEvaluation.Outcome.SUGGESTED; // a nurse must identify the employee first
        }
        IncidentEvaluation eval = evaluations.save(new IncidentEvaluation(incidentId, rule.orElse(null), outcome,
                facts.reporterEmail(), clock.instant()));
        if (outcome == IncidentEvaluation.Outcome.AUTO_CREATED) {
            IntakeRule r = rule.get();
            OhCase c = factory.open(facts, facts.employeeId(), r.getCaseType(), r.getUrgency());
            eval.caseCreated(c.getId(), IncidentEvaluation.Outcome.AUTO_CREATED, "rule:" + r.getId(), clock.instant());
        }
        log.info("Incident {} evaluated: {} (rule {})", incidentId, outcome, rule.map(IntakeRule::getId).orElse(null));
    }
}
