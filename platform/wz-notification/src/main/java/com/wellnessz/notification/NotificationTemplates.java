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
            "portalLink", "dueDate", "unit", "urgency", "nextStep", "contact", "queue", "level", "documentNumber", "employeeName", "site", "reference");
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
            Map.entry("result.abnormal", "A new item on case {{caseRef}} needs your acknowledgement ({{level}}). {{portalLink}}"),
            Map.entry("result.overdue", "An item on case {{caseRef}} has waited more than 72 hours for acknowledgement ({{level}}). {{portalLink}}"),
            Map.entry("duty.proposal", "Occupational Health has proposed modified duties for {{employeeName}} (case {{caseRef}}). Please accept or decline: {{portalLink}}"),
            Map.entry("job-offer.issued", "Hi {{firstName}}, you have received an alternative-duty offer. Please respond by {{dueDate}}: {{portalLink}}"),
            Map.entry("break-glass.alert", "Break-glass access was used ({{level}}). Review due by {{dueDate}}: {{portalLink}}"),
            Map.entry("fitness.signoff-required", "A fitness decision on case {{caseRef}} needs your sign-off ({{level}}). {{portalLink}}"),
            // increment 3 – E06 absence (status only: no reason, no certificate content)
            Map.entry("absence.oh-engaged", "Occupational Health is now supporting {{employeeName}} ({{unit}}) on case {{caseRef}}. No medical details are shared. Contact: {{contact}}"),
            Map.entry("absence.milestone-overdue", "A long-term absence milestone on case {{caseRef}} was due on {{dueDate}}. {{portalLink}}"),
            Map.entry("absence.advice-requested", "A management advice request on case {{caseRef}} ({{unit}}) is waiting, due by {{dueDate}}. {{portalLink}}"),
            Map.entry("absence.advice-review", "Hi {{firstName}}, an Occupational Health advice report is ready for you to review before it is released. Please sign in: {{portalLink}}"),
            Map.entry("absence.advice-released", "Occupational Health advice for {{employeeName}} (case {{caseRef}}) is available. {{portalLink}}"),
            Map.entry("absence.advice-declined", "The advice report on case {{caseRef}} has not been released. Occupational Health will contact you. {{portalLink}}"),
            Map.entry("absence.certificate-received", "Hi {{firstName}}, your absence certificate {{documentNumber}} has been received. {{portalLink}}"),
            Map.entry("absence.certificate-escalation", "An absence certificate check for {{employeeName}} ({{unit}}) needs HR follow-up. {{portalLink}}"),
            // increment 3 – E07 surveillance (never names the programme outcome)
            Map.entry("surveillance.invitation", "Hi {{firstName}}, you are due a routine Occupational Health appointment by {{dueDate}}. Book or view it here: {{portalLink}}"),
            Map.entry("surveillance.overdue-manager", "{{employeeName}} ({{unit}}) has not attended two invitations to a required health appointment due {{dueDate}}. Please support attendance: {{portalLink}}"),
            Map.entry("surveillance.follow-up", "A surveillance follow-up on case {{caseRef}} needs physician review by {{dueDate}}. {{portalLink}}"),
            Map.entry("crew.medical-expiring", "Hi {{firstName}}, your crew medical certificate expires on {{dueDate}} ({{level}}). Please book your renewal: {{portalLink}}"),
            Map.entry("crew.medical-expiring-licensing", "The crew medical certificate of {{employeeName}} expires on {{dueDate}} ({{level}}). {{portalLink}}"),
            Map.entry("crew.dose-review", "A cosmic dose review for {{employeeName}} is required ({{level}}). {{portalLink}}"),
            Map.entry("pre-employment.invitation", "Hi {{firstName}}, please complete your pre-employment health questionnaire and consent before {{dueDate}}: {{portalLink}}"),
            Map.entry("pre-employment.status", "The pre-employment clearance status for offer {{documentNumber}} has been updated. {{portalLink}}"),
            Map.entry("immunisation.due", "Hi {{firstName}}, a workplace vaccination appointment is due by {{dueDate}}. {{portalLink}}"),
            // increment 3 – E14 scheduling and clinic operations
            Map.entry("appointment.booked", "Hi {{firstName}}, your Occupational Health appointment is booked for {{appointmentDate}} at {{appointmentTime}} ({{site}}). Reschedule or cancel: {{portalLink}}"),
            Map.entry("appointment.cancelled", "Hi {{firstName}}, your Occupational Health appointment on {{appointmentDate}} at {{appointmentTime}} has been cancelled. {{portalLink}}"),
            Map.entry("appointment.waitlist-offer", "Hi {{firstName}}, an earlier Occupational Health appointment is available on {{appointmentDate}} at {{appointmentTime}} ({{site}}). {{portalLink}}"),
            Map.entry("coldchain.excursion", "Vaccine fridge {{documentNumber}} at {{site}} recorded a temperature excursion ({{level}}). Act now: {{portalLink}}"),
            Map.entry("equipment.calibration-due", "Equipment {{documentNumber}} at {{site}} is due for calibration on {{dueDate}}. {{portalLink}}"),
            // increment 4 – E04 treatment coordination and partner referrals (never names the service or the condition)
            Map.entry("referral.sent", "A new referral {{reference}} from WellnessZ Occupational Health is waiting for you in the partner portal: {{portalLink}}"),
            Map.entry("referral.chase", "The report for referral {{reference}} was due on {{dueDate}}. Please upload it in the partner portal: {{portalLink}}"),
            Map.entry("referral.overdue-team", "Referral {{reference}} on case {{caseRef}} is still waiting for the provider's report ({{level}}). {{portalLink}}"),
            Map.entry("referral.appointment", "Hi {{firstName}}, your appointment with an external provider is on {{appointmentDate}} at {{appointmentTime}}. Details: {{portalLink}}"),
            Map.entry("treatment.follow-up", "Hi {{firstName}}, a follow-up in your treatment plan is due on {{dueDate}}. {{portalLink}}"),
            Map.entry("treatment.variance", "Case {{caseRef}} has passed its expected recovery duration ({{level}}). Please review the plan: {{portalLink}}"),
            Map.entry("preauth.status", "Pre-authorisation {{reference}} on case {{caseRef}} is now {{level}}. {{portalLink}}"),
            Map.entry("telehealth.invite", "Hi {{firstName}}, your video consultation is on {{appointmentDate}} at {{appointmentTime}}. Join from the app: {{portalLink}}"),
            // increment 4 – E08 claims coordination (no costs, no clinical detail)
            Map.entry("claim.assigned", "Lost-time claim {{reference}} (case {{caseRef}}) has been assigned to you as nurse case manager. {{portalLink}}"),
            Map.entry("claim.report-due", "The statutory first report for claim {{reference}} is due by {{dueDate}}. {{portalLink}}"),
            Map.entry("claim.task", "A shared task on claim {{reference}} is due by {{dueDate}}. {{portalLink}}"),
            Map.entry("claim.message", "There is a new secure message on claim {{reference}}. Please sign in: {{portalLink}}"),
            Map.entry("legal.request", "Medico-legal request {{reference}} on case {{caseRef}} is due by {{dueDate}}. {{portalLink}}"));

    /** Approved template keys (for governance review and tests). */
    public java.util.Set<String> keys() {
        return templates.keySet();
    }

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
