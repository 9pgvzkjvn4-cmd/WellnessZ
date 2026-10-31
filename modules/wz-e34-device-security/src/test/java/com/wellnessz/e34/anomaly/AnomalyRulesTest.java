package com.wellnessz.e34.anomaly;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.e34.anomaly.AnomalyMatcher.CountMode;
import com.wellnessz.e34.anomaly.AnomalyMatcher.Match;
import com.wellnessz.e34.anomaly.AnomalyRuleRepository.AnomalyRule;
import com.wellnessz.e34.events.StoredSecurityEvent;

/** Event → rule mapping, thresholds, auto-isolation threshold and clinic-hours windows. */
class AnomalyRulesTest {

    static StoredSecurityEvent event(String type, String endpointId, String reason, String remote, Map<String, String> attrs) {
        return new StoredSecurityEvent(1, "e", type, "MEDIUM", endpointId, null, "LHR", "GATEWAY_ZONE", "device:x", "KERNEL", reason, remote,
                attrs, Instant.parse("2026-10-31T10:00:00Z"), "h");
    }

    static AnomalyRule rule(String code, int threshold, String action, Integer isolate) {
        return new AnomalyRule(code, "d", "HIGH", true, threshold, 5, action, isolate, "t", Instant.EPOCH);
    }

    @Test
    void authFailureFeedsBurstPerEndpointAndPerAddress() {
        List<Match> m = AnomalyMatcher.matches(event("ENDPOINT_AUTH_FAILED", "EP-1", "bad_token", "10.0.0.9", Map.of()));
        assertThat(m).extracting(Match::ruleCode, Match::subjectColumn, Match::subject, Match::isolatable)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("FAILED_AUTH_BURST", "endpoint_id", "EP-1", true),
                        org.assertj.core.groups.Tuple.tuple("FAILED_AUTH_BURST", "remote_address", "10.0.0.9", false));
    }

    @Test
    void reasonCodesMapToSpecificRules() {
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_AUTH_FAILED", "EP-404", "unknown_endpoint", "10.0.0.9", Map.of())))
                .extracting(Match::ruleCode).containsExactly("UNKNOWN_ENDPOINT", "FAILED_AUTH_BURST");
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_AUTH_FAILED", "EP-1", "zone_violation", null, Map.of())))
                .extracting(Match::ruleCode).containsExactly("FAILED_AUTH_BURST", "ZONE_VIOLATION");
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_AUTH_FAILED", "EP-1", "endpoint_forbidden", null, Map.of())))
                .extracting(Match::ruleCode).contains("UNEXPECTED_PROTOCOL");
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_AUTH_FAILED", "EP-1", "certificate_mismatch", null, Map.of())))
                .extracting(Match::ruleCode).contains("CERTIFICATE_ANOMALY");
    }

    @Test
    void edgeAndEngineEventsMapToTheirRules() {
        assertThat(AnomalyMatcher.matches(event("MESSAGE_RATE_SPIKE", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("MESSAGE_RATE_SPIKE");
        assertThat(AnomalyMatcher.matches(event("OFF_HOURS_TRAFFIC", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("OFF_HOURS_TRAFFIC");
        assertThat(AnomalyMatcher.matches(event("CLOCK_SKEW_DETECTED", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("CLOCK_SKEW");
        assertThat(AnomalyMatcher.matches(event("PAYLOAD_SCHEMA_ANOMALY", "EP-1", null, null, Map.of()))).extracting(Match::mode)
                .containsExactly(CountMode.EVENTS);
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_FIRST_AUTHENTICATED", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("NEW_DEVICE_IDENTITY");
        Match newDevice = AnomalyMatcher.matches(event("UNKNOWN_DEVICE_IDENTITY", "EP-1", null, null, Map.of("deviceSerialHash", "abc"))).get(0);
        assertThat(newDevice.ruleCode()).isEqualTo("NEW_DEVICE_IDENTITY");
        assertThat(newDevice.subject()).isEqualTo("EP-1:abc");
        assertThat(AnomalyMatcher.matches(event("CERTIFICATE_EXPIRING", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("CERTIFICATE_EXPIRING");
        assertThat(AnomalyMatcher.matches(event("HARDENING_DRIFT", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("HARDENING_DRIFT");
        assertThat(AnomalyMatcher.matches(event("UNEXPECTED_PORT", "EP-1", null, null, Map.of()))).extracting(Match::ruleCode)
                .containsExactly("UNEXPECTED_PORT");
        Match webhook = AnomalyMatcher.matches(event("WEBHOOK_SIGNATURE_FAILED", null, null, null, Map.of("connectorId", "DEV-9"))).get(0);
        assertThat(webhook.mode()).isEqualTo(CountMode.CONNECTOR);
        assertThat(webhook.subject()).isEqualTo("DEV-9");
        assertThat(AnomalyMatcher.matches(event("CONFIG_EXPORTED", null, null, null, Map.of()))).allMatch(Match::offHoursOnly);
        assertThat(AnomalyMatcher.matches(event("ENDPOINT_CREATED", "EP-1", null, null, Map.of()))).isEmpty();
    }

    @Test
    void failedAuthBurstAlertsAtTenAndIsolatesAtThirty() {
        AnomalyRule burst = rule("FAILED_AUTH_BURST", 10, "ISOLATE", 30);
        Match ep = new Match("FAILED_AUTH_BURST", CountMode.EVENTS, "endpoint_id", "EP-1", "ENDPOINT_AUTH_FAILED", null, true, false);
        Match addr = new Match("FAILED_AUTH_BURST", CountMode.EVENTS, "remote_address", "10.0.0.9", "ENDPOINT_AUTH_FAILED", null, false, false);
        assertThat(AnomalyMatcher.decide(burst, ep, 9)).isEqualTo(new AnomalyMatcher.Decision(false, false));
        assertThat(AnomalyMatcher.decide(burst, ep, 10)).isEqualTo(new AnomalyMatcher.Decision(true, false));
        assertThat(AnomalyMatcher.decide(burst, ep, 30)).isEqualTo(new AnomalyMatcher.Decision(true, true));
        // a source address is never "isolated" – alert only
        assertThat(AnomalyMatcher.decide(burst, addr, 50)).isEqualTo(new AnomalyMatcher.Decision(true, false));
        // without an isolation threshold, isolation happens at the alert threshold
        assertThat(AnomalyMatcher.decide(rule("FAILED_AUTH_BURST", 10, "ISOLATE", null), ep, 10).isolate()).isTrue();
        // alert-only rules never isolate; disabled rules never fire
        assertThat(AnomalyMatcher.decide(rule("FAILED_AUTH_BURST", 10, "ALERT", 30), ep, 99).isolate()).isFalse();
        AnomalyRule disabled = new AnomalyRule("FAILED_AUTH_BURST", "d", "HIGH", false, 10, 5, "ISOLATE", 30, "t", Instant.EPOCH);
        assertThat(AnomalyMatcher.decide(disabled, ep, 99).raise()).isFalse();
        assertThat(AnomalyMatcher.decide(null, ep, 99).raise()).isFalse();
    }

    @Test
    void singleOccurrenceRulesRaiseOnEveryEvent() {
        Match zone = new Match("ZONE_VIOLATION", CountMode.NONE, "endpoint_id", "EP-1", null, null, false, false);
        assertThat(AnomalyMatcher.decide(rule("ZONE_VIOLATION", 1, "REJECT_ALERT", null), zone, 1).raise()).isTrue();
        // rate-spike ceiling is applied by the traffic monitor, not as an event count
        Match spike = new Match("MESSAGE_RATE_SPIKE", CountMode.NONE, "endpoint_id", "EP-1", null, null, false, false);
        assertThat(AnomalyMatcher.decide(rule("MESSAGE_RATE_SPIKE", 600, "THROTTLE", null), spike, 1).raise()).isTrue();
    }

    @Test
    void clinicHoursWindows() {
        SiteHours.Window lhr = SiteHours.Window.parse("Europe/London", LocalTime.of(7, 0), LocalTime.of(20, 0), "MON,TUE,WED,THU,FRI,SAT");
        assertThat(lhr.days()).doesNotContain(DayOfWeek.SUNDAY).hasSize(6);
        assertThat(lhr.contains(Instant.parse("2026-10-30T09:00:00Z"))).isTrue();   // Friday 09:00 GMT
        assertThat(lhr.contains(Instant.parse("2026-10-30T21:30:00Z"))).isFalse();  // Friday 21:30
        assertThat(lhr.contains(Instant.parse("2026-11-01T10:00:00Z"))).isFalse();  // Sunday
        assertThat(lhr.contains(Instant.parse("2026-07-01T06:30:00Z"))).isTrue();   // 07:30 BST in summer
        SiteHours.Window night = SiteHours.Window.parse("UTC", LocalTime.of(22, 0), LocalTime.of(6, 0), "MON,TUE,WED,THU,FRI,SAT,SUN");
        assertThat(night.contains(Instant.parse("2026-10-30T23:00:00Z"))).isTrue();
        assertThat(night.contains(Instant.parse("2026-10-30T12:00:00Z"))).isFalse();
    }
}
