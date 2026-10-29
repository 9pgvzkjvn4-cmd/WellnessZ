package com.wellnessz.e01.notify;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.model.OhCaseRepository;
import com.wellnessz.casekernel.model.Urgency;
import com.wellnessz.common.events.IntegrationEnvelope;
import com.wellnessz.e01.rules.IncidentEvaluationRepository;
import com.wellnessz.integration.idempotency.IdempotentConsumer;
import com.wellnessz.masterdata.ConfigService;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;
import com.wellnessz.notification.NotificationService;
import com.wellnessz.notification.Priority;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the notification matrix to case events (E01-F08). Messages are content-free templates: case number, unit,
 * urgency, next step and contact – the notification gateway rejects anything that looks clinical.
 */
@Component
public class IntakeNotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(IntakeNotificationDispatcher.class);
    private static final Map<String, NotificationRule.Event> EVENTS = Map.of(
            "case.opened.v1", NotificationRule.Event.CASE_OPENED, "case.assigned.v1", NotificationRule.Event.CASE_ASSIGNED,
            "sla.at-risk.v1", NotificationRule.Event.SLA_AT_RISK, "sla.breached.v1", NotificationRule.Event.SLA_BREACHED,
            "case.escalated.v1", NotificationRule.Event.ESCALATED);

    private final NotificationRuleRepository rules;
    private final List<RecipientResolver> resolvers;
    private final OhCaseRepository cases;
    private final EmployeeRepository employees;
    private final IncidentEvaluationRepository evaluations;
    private final NotificationService notifications;
    private final ConfigService config;
    private final IdempotentConsumer consumer;
    private final JsonMapper json;
    private final String portalLink;
    private final String ohContact;

    public IntakeNotificationDispatcher(NotificationRuleRepository rules, List<RecipientResolver> resolvers, OhCaseRepository cases,
                                        EmployeeRepository employees, IncidentEvaluationRepository evaluations,
                                        NotificationService notifications, ConfigService config, IdempotentConsumer consumer,
                                        JsonMapper json,
                                        @Value("${wellnessz.notification.oh-workspace-url:https://oh.wellnessz.local}") String portalLink,
                                        @Value("${wellnessz.notification.oh-contact:Occupational Health service desk}") String ohContact) {
        this.rules = rules;
        this.resolvers = resolvers;
        this.cases = cases;
        this.employees = employees;
        this.evaluations = evaluations;
        this.notifications = notifications;
        this.config = config;
        this.consumer = consumer;
        this.json = json;
        this.portalLink = portalLink;
        this.ohContact = ohContact;
    }

    @EventListener(condition = "#envelope.eventType() matches '^(case\\.(opened|assigned|escalated)|sla\\.(at-risk|breached))\\.v1$'")
    public void onEvent(IntegrationEnvelope envelope) {
        NotificationRule.Event event = EVENTS.get(envelope.eventType());
        consumer.handleOnce("e01.notification-matrix", envelope, () -> {
            JsonNode n = json.readTree(envelope.payload());
            OhCase c = cases.findById(UUID.fromString(n.get("caseId").asString())).orElse(null);
            if (c == null) {
                return;
            }
            String level = n.has("level") ? n.get("level").asString() : null;
            for (NotificationRule r : rules.findByActiveTrue()) {
                if (r.appliesTo(event, c.getSource().name(), c.getCaseType().name())) {
                    for (RecipientResolver.Address a : addresses(r.getRecipient(), c)) {
                        send(r, c, a, level);
                    }
                }
            }
        });
    }

    private List<RecipientResolver.Address> addresses(NotificationRule.Recipient recipient, OhCase c) {
        List<RecipientResolver.Address> out = new ArrayList<>();
        switch (recipient) {
            case EMPLOYEE -> employees.findById(c.getEmployeeId()).filter(e -> e.getEmail() != null)
                    .ifPresent(e -> out.add(new RecipientResolver.Address(e.getEmployeeId(), e.getEmail())));
            case LINE_MANAGER -> employees.findById(c.getEmployeeId()).map(Employee::getManagerEmployeeId)
                    .flatMap(employees::findById).filter(m -> m.getEmail() != null)
                    .ifPresent(m -> out.add(new RecipientResolver.Address(m.getEmployeeId(), m.getEmail())));
            case HSE_REPORTER -> evaluations.findByCaseId(c.getId()).map(e -> e.getReporterEmail()).filter(s -> s != null)
                    .ifPresent(email -> out.add(new RecipientResolver.Address("hse", email)));
            case CREW_SCHEDULING -> {
                if (c.getSafetyCategory() != null && c.getSafetyCategory().isAeromedical()) {
                    config.get("notifications.crew-scheduling.email").ifPresent(email -> out.add(new RecipientResolver.Address("crew-scheduling", email)));
                }
            }
            case HYGIENE_MANAGER -> {
                if (c.getSafetyCategory() == com.wellnessz.casekernel.model.SafetyCategory.FOOD_HANDLER) {
                    config.get("notifications.hygiene-manager.email").ifPresent(email -> out.add(new RecipientResolver.Address("hygiene", email)));
                }
            }
            default -> resolvers.forEach(r -> out.addAll(r.resolve(recipient, c)));
        }
        return out;
    }

    private void send(NotificationRule r, OhCase c, RecipientResolver.Address a, String level) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("caseRef", c.getCaseRef());
        params.put("unit", c.getSubsidiaryCode() == null ? "OH" : c.getSubsidiaryCode());
        params.put("urgency", c.getUrgency().name());
        params.put("nextStep", nextStep(c));
        params.put("contact", ohContact);
        params.put("portalLink", portalLink);
        params.put("queue", c.getQueueCode() == null ? "unassigned" : c.getQueueCode());
        params.put("level", level == null ? "" : level);
        Priority priority = c.getUrgency() == Urgency.EMERGENCY || c.getUrgency() == Urgency.URGENT
                || r.getEvent() == NotificationRule.Event.SLA_BREACHED ? Priority.URGENT : Priority.NORMAL;
        try {
            // keep only placeholders the template uses (content rules reject unknown keys)
            notifications.enqueue(a.recipientId(), a.email(), r.getChannel(), priority, r.getTemplateKey(), filter(r.getTemplateKey(), params),
                    ZoneId.of("UTC"));
        } catch (RuntimeException e) {
            log.warn("Notification rule {} not sent for case {}: {}", r.getId(), c.getCaseRef(), e.getClass().getSimpleName());
        }
    }

    static Map<String, String> filter(String templateKey, Map<String, String> all) {
        return switch (templateKey) {
            case "case.opened.team" -> pick(all, "caseRef", "unit", "urgency", "nextStep", "portalLink");
            case "case.opened.stakeholder" -> pick(all, "caseRef", "unit", "nextStep", "contact");
            case "case.assigned" -> pick(all, "caseRef", "unit", "urgency", "portalLink");
            case "sla.alert" -> pick(all, "level", "caseRef", "unit", "queue", "portalLink");
            case "escalation.raised" -> pick(all, "caseRef", "level", "portalLink");
            default -> pick(all, "caseRef", "portalLink");
        };
    }

    private static Map<String, String> pick(Map<String, String> all, String... keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            out.put(k, all.getOrDefault(k, ""));
        }
        return out;
    }

    static String nextStep(OhCase c) {
        return switch (c.getStatus()) {
            case INTAKE -> "triage by the OH team";
            case TRIAGE -> "clinical assessment";
            default -> "follow-up by the OH team";
        };
    }
}
