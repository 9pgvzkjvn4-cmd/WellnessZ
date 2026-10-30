package com.wellnessz.e09.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CismRulesTest {

    static final Instant T = Instant.parse("2026-10-28T15:00:00Z");
    static final List<String> CLASSES = List.of("FATALITY", "ASSAULT", "FUME_EVENT", "AIRCRAFT_ACCIDENT", "SERIOUS_INJURY", "EMERGENCY_LANDING");

    static CismRules.Duty duty(String emp, String ref, String group, String role, String from, String to) {
        return new CismRules.Duty(emp, ref, group, role, Instant.parse(from), Instant.parse(to));
    }

    @Test
    void triggersOnSeverityOrConfiguredClass() {
        assertThat(CismRules.triggers("HIGH", null, CLASSES)).isTrue();
        assertThat(CismRules.triggers("critical", "SLIP", CLASSES)).isTrue();
        assertThat(CismRules.triggers("LOW", "fume_event", CLASSES)).isTrue();
        assertThat(CismRules.triggers("MEDIUM", "SLIP", CLASSES)).isFalse();
        assertThat(CismRules.triggers(null, null, CLASSES)).isFalse();
    }

    @Test
    void affectedListIsTheIncidentEmployeeAndCoWorkersOnTheSameDutyOrGroupWithinTheWindow() {
        CismRules.Window w = CismRules.window(T, 2);
        assertThat(w.from()).isEqualTo(T.minus(Duration.ofHours(2)));
        assertThat(w.to()).isEqualTo(T.plus(Duration.ofHours(2)));
        List<CismRules.Duty> own = List.of(duty("E1002", "WZ101", "G-WZAA", "FIRST_OFFICER", "2026-10-28T09:00:00Z", "2026-10-28T22:30:00Z"));
        assertThat(CismRules.dutyRefs(own)).containsExactly("WZ101");
        assertThat(CismRules.proximityGroups(own)).containsExactly("G-WZAA");
        List<CismRules.Duty> co = List.of(
                duty("E1002", "WZ101", "G-WZAA", "FIRST_OFFICER", "2026-10-28T09:00:00Z", "2026-10-28T22:30:00Z"),
                duty("E1003", "WZ101", "G-WZAA", "CABIN_CREW", "2026-10-28T09:00:00Z", "2026-10-28T22:30:00Z"),
                duty("E7001", "WZ101", "G-WZAA", "CAPTAIN", "2026-10-28T09:00:00Z", "2026-10-28T22:30:00Z"),
                // same aircraft, but the previous rotation ended more than two hours before the event
                duty("E1006", "WZ099", "G-WZAA", "ENGINEER", "2026-10-28T05:00:00Z", "2026-10-28T12:30:00Z"),
                // ground engineer whose shift touches the window
                duty("E1008", "TURN-1", "G-WZAA", "ENGINEER", "2026-10-28T16:30:00Z", "2026-10-28T20:00:00Z"),
                duty("E1003", "WZ101", "G-WZAA", "CABIN_CREW", "2026-10-28T09:00:00Z", "2026-10-28T22:30:00Z"));
        Map<String, String> affected = CismRules.affected("E1002", own, co, w);
        assertThat(affected.keySet()).containsExactly("E1002", "E1003", "E7001", "E1008");
        assertThat(affected.get("E1002")).isEqualTo("FIRST_OFFICER");
        assertThat(affected.get("E7001")).isEqualTo("CAPTAIN");
    }

    @Test
    void manualEventsWithoutAnIncidentEmployeeListTheDutyOnly() {
        CismRules.Window w = CismRules.window(T, 2);
        Map<String, String> affected = CismRules.affected(null, List.of(),
                List.of(duty("E1004", "RAMP-EARLY", "LHR-STAND-5", "RAMP_AGENT", "2026-10-28T13:30:00Z", "2026-10-28T21:00:00Z")), w);
        assertThat(affected).containsOnlyKeys("E1004");
        assertThat(CismRules.affected(" ", List.of(), List.of(), w)).isEmpty();
    }

    @Test
    void offerSlaIs72HoursFromTheEvent() {
        Instant due = CismRules.offerDueBy(T, 72);
        assertThat(due).isEqualTo(Instant.parse("2026-10-31T15:00:00Z"));
        Instant inTime = due.minus(Duration.ofHours(1));
        Instant late = due.plus(Duration.ofMinutes(1));
        assertThat(CismRules.slaMet(due, List.of(inTime, inTime), due.plus(Duration.ofDays(3)))).isTrue();
        assertThat(CismRules.slaMet(due, List.of(inTime, late), due.plus(Duration.ofDays(3)))).isFalse();
        // pending inside the window: not yet decided
        assertThat(CismRules.slaMet(due, Arrays.asList(inTime, null), due.minus(Duration.ofHours(5)))).isNull();
        // pending after the deadline: missed
        assertThat(CismRules.slaMet(due, Arrays.asList(inTime, null), late)).isFalse();
        assertThat(CismRules.slaMet(due, List.of(), late)).isTrue();
    }

    @Test
    void followUpsAtOneAndFourWeeks() {
        CismRules.FollowUps f = CismRules.followUps(LocalDate.of(2026, 10, 28));
        assertThat(f.oneWeek()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(f.fourWeeks()).isEqualTo(LocalDate.of(2026, 11, 25));
    }
}
