package com.wellnessz.e34.anomaly;

import java.util.ArrayList;
import java.util.List;

import com.wellnessz.e34.anomaly.AnomalyRuleRepository.AnomalyRule;
import com.wellnessz.e34.events.StoredSecurityEvent;

/**
 * Pure mapping from a stored security event to the anomaly rules it feeds (contract §15.8) and the threshold decision.
 * Unit-tested; the engine performs the counting and the side effects.
 */
public final class AnomalyMatcher {

    /** How the engine counts occurrences for the threshold. */
    public enum CountMode { NONE, EVENTS, CONNECTOR }

    /**
     * @param subjectColumn  column the count is grouped by ({@code endpoint_id}, {@code remote_address}, {@code device_instance_id})
     * @param subject        value of the subject (alert de-duplication key)
     * @param countType      event type that is counted
     * @param countReason    reason code that is counted ({@code null} = any)
     * @param isolatable     the subject is an endpoint that may be isolated by the rule
     * @param offHoursOnly   the rule applies only outside the clinic hours of the site
     */
    public record Match(String ruleCode, CountMode mode, String subjectColumn, String subject, String countType, String countReason,
                        boolean isolatable, boolean offHoursOnly) {
    }

    /** @param raise create / update the alert; @param isolate isolate the endpoint (action ISOLATE at its threshold) */
    public record Decision(boolean raise, boolean isolate) {
    }

    private AnomalyMatcher() {
    }

    public static List<Match> matches(StoredSecurityEvent e) {
        List<Match> out = new ArrayList<>();
        String ep = e.endpointId();
        String remote = e.remoteAddress();
        String reason = e.reasonCode();
        switch (e.type()) {
            case "ENDPOINT_AUTH_FAILED" -> {
                if ("unknown_endpoint".equals(reason) && remote != null) {
                    out.add(new Match("UNKNOWN_ENDPOINT", CountMode.EVENTS, "remote_address", remote, e.type(), "unknown_endpoint", false, false));
                }
                if (ep != null && !"unknown_endpoint".equals(reason)) {
                    out.add(new Match("FAILED_AUTH_BURST", CountMode.EVENTS, "endpoint_id", ep, e.type(), null, true, false));
                }
                if (remote != null) {
                    out.add(new Match("FAILED_AUTH_BURST", CountMode.EVENTS, "remote_address", remote, e.type(), null, false, false));
                }
                if (ep != null && "zone_violation".equals(reason)) {
                    out.add(endpointRule("ZONE_VIOLATION", ep));
                }
                if (ep != null && "endpoint_forbidden".equals(reason)) {
                    out.add(endpointRule("UNEXPECTED_PROTOCOL", ep));
                }
                if (ep != null && "certificate_mismatch".equals(reason)) {
                    out.add(endpointRule("CERTIFICATE_ANOMALY", ep));
                }
            }
            case "ZONE_VIOLATION" -> addIf(out, ep, "ZONE_VIOLATION");
            case "UNEXPECTED_PROTOCOL" -> addIf(out, ep, "UNEXPECTED_PROTOCOL");
            case "UNEXPECTED_PORT" -> addIf(out, ep, "UNEXPECTED_PORT");
            case "HARDENING_DRIFT" -> addIf(out, ep, "HARDENING_DRIFT");
            case "CERTIFICATE_EXPIRING" -> addIf(out, ep, "CERTIFICATE_EXPIRING");
            case "MESSAGE_RATE_SPIKE" -> addIf(out, ep, "MESSAGE_RATE_SPIKE");
            case "OFF_HOURS_TRAFFIC" -> addIf(out, ep, "OFF_HOURS_TRAFFIC");
            case "CLOCK_SKEW_DETECTED" -> addIf(out, ep != null ? ep : e.deviceInstanceId(), "CLOCK_SKEW");
            case "ENDPOINT_FIRST_AUTHENTICATED" -> addIf(out, ep, "NEW_DEVICE_IDENTITY");
            case "UNKNOWN_DEVICE_IDENTITY" -> {
                String serial = e.attribute("deviceSerialHash");
                String subject = (ep == null ? "?" : ep) + (serial == null ? "" : ":" + serial);
                out.add(new Match("NEW_DEVICE_IDENTITY", CountMode.NONE, null, subject, e.type(), null, false, false));
            }
            case "PAYLOAD_SCHEMA_ANOMALY" -> {
                if (ep != null) {
                    out.add(new Match("PAYLOAD_SCHEMA_ANOMALY", CountMode.EVENTS, "endpoint_id", ep, e.type(), null, false, false));
                }
            }
            case "WEBHOOK_SIGNATURE_FAILED" -> {
                String connector = e.attribute("connectorId") != null ? e.attribute("connectorId")
                        : e.deviceInstanceId() != null ? e.deviceInstanceId() : ep;
                if (connector != null) {
                    out.add(new Match("WEBHOOK_SIGNATURE_FAILURES", CountMode.CONNECTOR, null, connector, e.type(), null, false, false));
                }
            }
            case "CONFIG_EXPORTED", "SECRET_METADATA_VIEWED" ->
                    out.add(new Match("OFF_HOURS_CONFIG_ACCESS", CountMode.NONE, null, e.actor(), e.type(), null, false, true));
            default -> {
                // no rule
            }
        }
        return out;
    }

    private static Match endpointRule(String code, String ep) {
        return new Match(code, CountMode.NONE, "endpoint_id", ep, null, null, false, false);
    }

    private static void addIf(List<Match> out, String subject, String code) {
        if (subject != null) {
            out.add(new Match(code, CountMode.NONE, "endpoint_id", subject, null, null, false, false));
        }
    }

    /**
     * Threshold decision. Rules without counting (single-occurrence rules, the rate-spike rule whose ceiling is applied by the
     * traffic monitor and the certificate rule whose threshold is in days) raise on every matching event.
     */
    public static Decision decide(AnomalyRule rule, Match match, int count) {
        if (rule == null || !rule.enabled()) {
            return new Decision(false, false);
        }
        boolean raise = match.mode() == CountMode.NONE || count >= rule.threshold();
        boolean isolate = false;
        if (raise && match.isolatable() && "ISOLATE".equals(rule.action())) {
            int at = rule.isolateThreshold() == null ? rule.threshold() : rule.isolateThreshold();
            isolate = match.mode() == CountMode.NONE || count >= at;
        }
        return new Decision(raise, isolate);
    }
}
