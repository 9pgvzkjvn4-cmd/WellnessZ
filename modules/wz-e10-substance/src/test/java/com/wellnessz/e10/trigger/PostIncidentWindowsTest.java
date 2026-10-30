package com.wellnessz.e10.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class PostIncidentWindowsTest {

    static final List<PostIncidentRules.Rule> RULES = List.of(
            new PostIncidentRules.Rule(1, "Near miss – no test", 5, true, "NEAR_MISS", null, "IGNORE"),
            new PostIncidentRules.Rule(2, "Lost-time injury", 10, true, "LOST_TIME_INJURY", null, "TEST"),
            new PostIncidentRules.Rule(3, "Property damage – high", 40, true, "PROPERTY_DAMAGE", "HIGH", "TEST"),
            new PostIncidentRules.Rule(4, "Inactive", 1, false, null, null, "TEST"),
            new PostIncidentRules.Rule(5, "Any – high severity", 90, true, null, "HIGH", "TEST"));

    @Test
    void ruleTableDecidesOnClassAndSeverity() {
        assertThat(PostIncidentRules.qualifies(RULES, "LOST_TIME_INJURY", "LOW")).isTrue();
        assertThat(PostIncidentRules.qualifies(RULES, "PROPERTY_DAMAGE", "MEDIUM")).isFalse();
        assertThat(PostIncidentRules.qualifies(RULES, "PROPERTY_DAMAGE", "critical")).isTrue();
        assertThat(PostIncidentRules.qualifies(RULES, "NEAR_MISS", "CRITICAL")).isFalse();      // first matching rule wins
        assertThat(PostIncidentRules.qualifies(RULES, "SLIP_TRIP", "HIGH")).isTrue();            // catch-all by severity
        assertThat(PostIncidentRules.qualifies(RULES, "SLIP_TRIP", "MEDIUM")).isFalse();
        assertThat(PostIncidentRules.qualifies(RULES, null, null)).isFalse();
        assertThat(PostIncidentRules.match(RULES, "LOST_TIME_INJURY", null).orElseThrow().id()).isEqualTo(2);
    }

    @Test
    void windowsRunFromTheEvent() {
        Instant event = Instant.parse("2026-10-30T06:00:00Z");
        TestWindows.Windows w = TestWindows.windows(event, 8, 32);
        assertThat(w.alcoholDueAt()).isEqualTo(Instant.parse("2026-10-30T14:00:00Z"));
        assertThat(w.drugDueAt()).isEqualTo(Instant.parse("2026-10-31T14:00:00Z"));
        assertThat(w.nextDue(true, false, true)).isEqualTo(w.alcoholDueAt());
        assertThat(w.nextDue(true, true, true)).isEqualTo(w.drugDueAt());
        assertThat(w.nextDue(true, false, false)).isEqualTo(w.alcoholDueAt());
    }

    @Test
    void countdownAndInWindow() {
        Instant now = Instant.parse("2026-10-30T09:00:00Z");
        assertThat(TestWindows.hoursLeft(now, Instant.parse("2026-10-30T14:00:00Z"))).isEqualByComparingTo("5.0");
        assertThat(TestWindows.hoursLeft(now, Instant.parse("2026-10-30T09:59:00Z"))).isEqualByComparingTo("0.9");
        assertThat(TestWindows.hoursLeft(now, Instant.parse("2026-10-30T08:00:00Z"))).isEqualByComparingTo("-1.0");
        assertThat(TestWindows.hoursLeft(now, null)).isNull();
        assertThat(TestWindows.inWindow(Instant.parse("2026-10-30T13:59:59Z"), Instant.parse("2026-10-30T14:00:00Z"))).isTrue();
        assertThat(TestWindows.inWindow(Instant.parse("2026-10-30T14:00:01Z"), Instant.parse("2026-10-30T14:00:00Z"))).isFalse();
    }
}
