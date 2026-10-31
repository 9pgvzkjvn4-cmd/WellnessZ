package com.wellnessz.e34.anomaly;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.wellnessz.e34.anomaly.AnomalyMatcher.Decision;
import com.wellnessz.e34.anomaly.AnomalyMatcher.Match;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository.AnomalyRule;
import com.wellnessz.e34.events.SecurityEventListener;
import com.wellnessz.e34.events.SecurityEventService;
import com.wellnessz.e34.events.StoredSecurityEvent;
import com.wellnessz.e34.identity.EndpointService;
import com.wellnessz.e34.support.E34Settings;

/**
 * Evaluates the anomaly rules on every committed security event (E34-HLR-09, contract §15.8): counts occurrences in the rule
 * window, raises / updates the alert and, for rules with action ISOLATE at their isolation threshold, sets the playbook
 * deadline ({@code detected + device.security.isolation-sla-minutes}) and isolates the endpoint at once. Should the immediate
 * isolation fail (e.g. a lock), the isolation sweep completes it well within the deadline.
 */
@Component
public class AnomalyEngine implements SecurityEventListener {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEngine.class);

    private final AnomalyRuleRepository rules;
    private final AlertService alerts;
    private final SiteHours hours;
    private final ObjectProvider<SecurityEventService> events;
    private final ObjectProvider<EndpointService> endpoints;
    private final E34Settings settings;
    private final TransactionTemplate requiresNew;

    public AnomalyEngine(AnomalyRuleRepository rules, AlertService alerts, SiteHours hours, ObjectProvider<SecurityEventService> events,
                         ObjectProvider<EndpointService> endpoints, E34Settings settings, PlatformTransactionManager tm) {
        this.rules = rules;
        this.alerts = alerts;
        this.hours = hours;
        this.events = events;
        this.endpoints = endpoints;
        this.settings = settings;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void onSecurityEvent(StoredSecurityEvent e) {
        if (!settings.enabled()) {
            return;
        }
        for (Match m : AnomalyMatcher.matches(e)) {
            AnomalyRule rule = rules.get(m.ruleCode()).orElse(null);
            if (rule == null || !rule.enabled()) {
                continue;
            }
            if (m.offHoursOnly() && !hours.outsideHours(e.siteCode(), e.occurredAt())) {
                continue;
            }
            int count = count(m, rule, e.occurredAt());
            Decision d = AnomalyMatcher.decide(rule, m, count);
            if (!d.raise()) {
                continue;
            }
            String endpointId = "endpoint_id".equals(m.subjectColumn()) ? m.subject() : e.endpointId();
            AlertService.Raised raised = alerts.raise(rule, m.subject(), endpointId, e.siteCode(), e.id());
            if (d.isolate() && endpointId != null) {
                isolate(raised.alertId(), endpointId, rule.code(), raised.detectedAt());
            }
        }
    }

    private int count(Match m, AnomalyRule rule, Instant at) {
        Instant since = at.minus(rule.windowMinutes(), ChronoUnit.MINUTES);
        return switch (m.mode()) {
            case NONE -> 1;
            case EVENTS -> events.getObject().count(m.countType(), m.subjectColumn(), m.subject(), m.countReason(), since);
            case CONNECTOR -> events.getObject().countByConnector(m.countType(), m.subject(), since);
        };
    }

    private void isolate(UUID alertId, String endpointId, String ruleCode, Instant detectedAt) {
        alerts.setIsolationDue(alertId, detectedAt.plus(settings.isolationSlaMinutes(), ChronoUnit.MINUTES));
        try {
            requiresNew.executeWithoutResult(s -> endpoints.getObject().isolateAutomatically(endpointId, alertId, "ANOMALY_" + ruleCode, detectedAt));
        } catch (RuntimeException ex) {
            log.warn("Immediate isolation deferred to the sweep ({}): {}", ruleCode, ex.getClass().getSimpleName());
        }
    }

    /** Isolation sweep: completes every isolation whose alert is still waiting (multi-replica safe through the row lock). */
    public int sweep() {
        if (!settings.enabled()) {
            return 0;
        }
        int done = 0;
        for (AlertService.DueIsolation d : alerts.dueIsolations(50)) {
            try {
                requiresNew.executeWithoutResult(s -> endpoints.getObject().isolateAutomatically(d.endpointId(), d.alertId(),
                        "ANOMALY_" + d.ruleCode(), d.detectedAt()));
                done++;
            } catch (RuntimeException ex) {
                log.warn("Isolation sweep failed for an alert: {}", ex.getClass().getSimpleName());
            }
        }
        return done;
    }
}
