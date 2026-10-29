package com.wellnessz.notification;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Approved notification templates. Content rule: notifications are <b>content-free nudges</b> – they never carry
 * diagnoses, clinical findings or test results, only a prompt to sign in. Templates are reviewed by OH governance
 * and later become configurable through E18.
 */
@Component
public class NotificationTemplates {

    static final Set<String> ALLOWED_PLACEHOLDERS = Set.of("firstName", "appointmentDate", "appointmentTime", "caseRef",
            "portalLink", "dueDate", "unit", "urgency", "nextStep", "contact", "queue", "level", "documentNumber", "employeeName");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z]+)}}");

    private final Map<String, String> templates = Map.ofEntries(
            Map.entry("appointment.reminder", "Hi {{firstName}}, you have an Occupational Health appointment on {{appointmentDate}} at {{appointmentTime}}. Details: {{portalLink}}"),
            Map.entry("case.update", "Hi {{firstName}}, there is an update on your Occupational Health case {{caseRef}}. Please sign in: {{portalLink}}"),
            Map.entry("action.required", "Hi {{firstName}}, an action is due by {{dueDate}} in your Occupational Health portal: {{portalLink}}"),
            Map.entry("test.message", "WellnessZ notification test for {{firstName}}."),
            // increment 1 – E01-F08 / E02 (content-free by design: case number, unit, urgency, next step, contact)
            Map.entry("case.opened.team", "New OH case {{caseRef}} ({{unit}}), urgency {{urgency}}. Next step: {{nextStep}}. Open the OH workspace: {{portalLink}}"),
            Map.entry("case.opened.stakeholder", "An Occupational Health case {{caseRef}} has been opened for a member of {{unit}}. Next step: {{nextStep}}. Contact: {{contact}}"),
            Map.entry("case.assigned", "OH case {{caseRef}} ({{unit}}) has been assigned to you, urgency {{urgency}}. {{portalLink}}"),
            Map.entry("sla.alert", "SLA {{level}} on OH case {{caseRef}} ({{unit}}), queue {{queue}}. {{portalLink}}"),
            Map.entry("escalation.raised", "OH case {{caseRef}} has been escalated to {{level}}. Please acknowledge: {{portalLink}}"),
            Map.entry("advice.available", "Hi {{firstName}}, Occupational Health has shared advice on case {{caseRef}}. Please sign in: {{portalLink}}"),
            Map.entry("handover.received", "OH case {{caseRef}} has been handed over to you. Please acknowledge: {{portalLink}}"),
            // increment 2 (E03 / E05)
            Map.entry("document.shared", "Hi {{firstName}}, Occupational Health has shared document {{documentNumber}} with you. Please sign in: {{portalLink}}"),
            Map.entry("certificate.available", "Hi {{firstName}}, a fitness certificate ({{documentNumber}}) is available. Please sign in to view and acknowledge it: {{portalLink}}"),
            Map.entry("rtw.plan-ack", "A return-to-work plan for case {{caseRef}} needs your acknowledgement by {{dueDate}}: {{portalLink}}"),
            Map.entry("rtw.review-due", "A return-to-work review for case {{caseRef}} is due on {{dueDate}}. {{portalLink}}"),
            Map.entry("restriction.expiring", "A work restriction for {{employeeName}} (case {{caseRef}}) ends on {{dueDate}}. Please review: {{portalLink}}"),
            Map.entry("result.abnormal", "An abnormal result needs acknowledgement on case {{caseRef}}. {{portalLink}}"),
            Map.entry("result.overdue", "An abnormal result on case {{caseRef}} has not been acknowledged within 72 hours ({{level}}). {{portalLink}}"),
            Map.entry("duty.proposal", "Occupational Health has proposed modified duties for {{employeeName}} (case {{caseRef}}). Please accept or decline: {{portalLink}}"),
            Map.entry("job-offer.issued", "Hi {{firstName}}, you have received an alternative-duty offer. Please respond by {{dueDate}}: {{portalLink}}"),
            Map.entry("break-glass.alert", "Break-glass access was used ({{level}}). Review due by {{dueDate}}: {{portalLink}}"),
            Map.entry("fitness.signoff-required", "A fitness decision on case {{caseRef}} needs your sign-off ({{level}}). {{portalLink}}"));

    public String render(String templateKey, Map<String, String> params) {
        String template = templates.get(templateKey);
        if (template == null) {
            throw new ContentRuleViolation("Unknown template");
        }
        for (String key : params.keySet()) {
            if (!ALLOWED_PLACEHOLDERS.contains(key)) {
                throw new ContentRuleViolation("Parameter not permitted in notifications: " + key);
            }
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = params.getOrDefault(m.group(1), "");
            m.appendReplacement(out, Matcher.quoteReplacement(sanitise(value)));
        }
        m.appendTail(out);
        ContentRules.assertNoClinicalContent(out.toString());
        return out.toString();
    }

    private static String sanitise(String value) {
        String v = value.replaceAll("[\\p{Cntrl}<>]", "");
        return v.length() > 120 ? v.substring(0, 120) : v;
    }

    public static class ContentRuleViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ContentRuleViolation(String message) {
            super(message);
        }
    }
}
