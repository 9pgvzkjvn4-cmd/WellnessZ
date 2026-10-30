package com.wellnessz.e11.communicable;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.e11.communicable.TracingRules.ContactType;
import com.wellnessz.e11.communicable.TracingRules.Duty;
import com.wellnessz.e11.communicable.TracingRules.Found;
import com.wellnessz.e11.communicable.TracingRules.Risk;

class TracingRulesTest {

    static final Instant DAY = Instant.parse("2026-10-29T00:00:00Z");
    static final Set<ContactType> ALL = EnumSet.allOf(ContactType.class);

    static Instant at(int hour, int minute) {
        return DAY.plusSeconds(hour * 3600L + minute * 60L);
    }

    static Duty shift(String employee, String ref, String group, Instant from, Instant to) {
        return new Duty(employee, "SHIFT", ref, group, from, to);
    }

    @Test
    void overlapIsClippedToBothDutiesAndTheWindow() {
        assertThat(TracingRules.overlapMinutes(at(4, 0), at(12, 0), at(6, 0), at(14, 0), at(0, 0), at(23, 0))).isEqualTo(360);
        assertThat(TracingRules.overlapMinutes(at(4, 0), at(12, 0), at(6, 0), at(14, 0), at(0, 0), at(8, 0))).isEqualTo(120);
        assertThat(TracingRules.overlapMinutes(at(4, 0), at(6, 0), at(6, 0), at(8, 0), at(0, 0), at(23, 0))).isZero();
        assertThat(TracingRules.overlapMinutes(at(4, 0), at(6, 0), at(7, 0), at(8, 0), at(0, 0), at(23, 0))).isZero();
    }

    @Test
    void riskLevels() {
        assertThat(TracingRules.risk(ContactType.PROXIMITY_GROUP, 15, 15)).isEqualTo(Risk.HIGH);
        assertThat(TracingRules.risk(ContactType.PROXIMITY_GROUP, 14, 15)).isEqualTo(Risk.LOW);
        assertThat(TracingRules.risk(ContactType.SAME_DUTY, 600, 15)).isEqualTo(Risk.MEDIUM);
        assertThat(TracingRules.risk(ContactType.FACILITY, 45, 15)).isEqualTo(Risk.MEDIUM);
        assertThat(TracingRules.risk(ContactType.FACILITY, 5, 15)).isEqualTo(Risk.LOW);
    }

    @Test
    void cateringLineShiftGivesHighRiskContactsInTheSameWorkArea() {
        Duty index = shift("E1001", "CAT-LINE-2", "LHR-CAT-LINE-2", at(4, 0), at(12, 0));
        List<Duty> candidates = List.of(index,
                shift("E1010", "CAT-LINE-2", "LHR-CAT-LINE-2", at(4, 0), at(12, 0)),
                shift("M2001", "CAT-LINE-2", "LHR-CAT-LINE-2", at(6, 0), at(14, 0)),
                shift("E1099", "CAT-LINE-2", "LHR-CAT-LINE-3", at(11, 50), at(18, 0)),
                shift("E1098", "CAT-LINE-2", "LHR-CAT-LINE-2", at(13, 0), at(18, 0)));
        List<Found> found = TracingRules.trace("E1001", List.of(index), candidates, ALL, at(0, 0), at(23, 59), 15);
        assertThat(found).extracting(Found::employeeId).containsExactly("E1010", "M2001", "E1099");
        assertThat(found.get(0)).isEqualTo(new Found("E1010", ContactType.PROXIMITY_GROUP, "CAT-LINE-2", 480, Risk.HIGH));
        assertThat(found.get(1).overlapMinutes()).isEqualTo(360);
        assertThat(found.get(1).risk()).isEqualTo(Risk.HIGH);
        // same shift but a different work area, 10 minutes overlap → same duty, low risk
        assertThat(found.get(2)).isEqualTo(new Found("E1099", ContactType.SAME_DUTY, "CAT-LINE-2", 10, Risk.LOW));
    }

    @Test
    void includeFilterRestrictsContactTypes() {
        Duty index = shift("A", "WZ101", "G-WZAA", at(9, 0), at(22, 0));
        Duty sameAircraft = shift("B", "WZ101", "G-WZAA", at(9, 0), at(22, 0));
        List<Found> sameDutyOnly = TracingRules.trace("A", List.of(index), List.of(sameAircraft), EnumSet.of(ContactType.SAME_DUTY), at(0, 0),
                at(23, 0), 15);
        assertThat(sameDutyOnly).singleElement().satisfies(f -> {
            assertThat(f.contactType()).isEqualTo(ContactType.SAME_DUTY);
            assertThat(f.risk()).isEqualTo(Risk.MEDIUM);
        });
        assertThat(TracingRules.trace("A", List.of(index), List.of(sameAircraft), EnumSet.of(ContactType.FACILITY), at(0, 0), at(23, 0), 15))
                .isEmpty();
    }

    @Test
    void sharedFacilityContactsAreFacilityTypeAndCapped() {
        Duty index = new Duty("E1003", "FACILITY", "LHR-CREW-ROOM", "LHR-CREW-ROOM", at(7, 30), at(9, 0));
        Duty other = new Duty("E1006", "FACILITY", "LHR-CREW-ROOM", "LHR-CREW-ROOM", at(8, 0), at(8, 45));
        List<Found> found = TracingRules.trace("E1003", List.of(index), List.of(other), ALL, at(0, 0), at(23, 0), 15);
        assertThat(found).singleElement().isEqualTo(new Found("E1006", ContactType.FACILITY, "LHR-CREW-ROOM", 45, Risk.MEDIUM));
        assertThat(TracingRules.trace("E1003", List.of(index), List.of(other), EnumSet.of(ContactType.PROXIMITY_GROUP), at(0, 0), at(23, 0), 15))
                .isEmpty();
    }

    @Test
    void overlapsAcrossSeveralDutiesAreSummedAndTheIndexIsExcluded() {
        Duty out = shift("A", "WZ101", "G-WZAA", at(1, 0), at(2, 0));
        Duty back = shift("A", "WZ102", "G-WZAA", at(10, 0), at(10, 10));
        List<Duty> candidates = List.of(out, back, shift("B", "WZ101", "G-WZAA", at(1, 0), at(2, 0)), shift("B", "WZ102", "G-WZAA", at(10, 0), at(10, 10)));
        List<Found> found = TracingRules.trace("A", List.of(out, back), candidates, ALL, at(0, 0), at(23, 0), 15);
        assertThat(found).singleElement().satisfies(f -> {
            assertThat(f.employeeId()).isEqualTo("B");
            assertThat(f.overlapMinutes()).isEqualTo(70);
            assertThat(f.risk()).isEqualTo(Risk.HIGH);
        });
    }

    @Test
    void monitoringScheduleCoversTheMonitoringDays() {
        LocalDate start = LocalDate.of(2026, 10, 30);
        assertThat(MonitoringSchedule.checkInDays(start, 3)).containsExactly(start, start.plusDays(1), start.plusDays(2));
        assertThat(MonitoringSchedule.monitoringUntil(start, 3)).isEqualTo(start.plusDays(2));
        assertThat(MonitoringSchedule.checkInDays(start, 0)).isEmpty();
        assertThat(MonitoringSchedule.monitoringUntil(start, 0)).isNull();
    }

    @Test
    void symptomRules() {
        BigDecimal fever = new BigDecimal("38.0");
        assertThat(MonitoringSchedule.validSymptoms(List.of("NONE"))).isTrue();
        assertThat(MonitoringSchedule.validSymptoms(List.of("NONE", "COUGH"))).isFalse();
        assertThat(MonitoringSchedule.validSymptoms(List.of("HEADACHE"))).isFalse();
        assertThat(MonitoringSchedule.validSymptoms(List.of())).isFalse();
        assertThat(MonitoringSchedule.symptomatic(List.of("NONE"), new BigDecimal("36.8"), fever)).isFalse();
        assertThat(MonitoringSchedule.symptomatic(List.of("NONE"), new BigDecimal("38.2"), fever)).isTrue();
        assertThat(MonitoringSchedule.symptomatic(List.of("VOMITING"), null, fever)).isTrue();
    }
}
