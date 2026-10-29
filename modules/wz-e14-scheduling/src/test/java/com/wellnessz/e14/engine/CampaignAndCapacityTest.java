package com.wellnessz.e14.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class CampaignAndCapacityTest {

    @Test
    void campaignSlotsAreDaysTimesStationsTimesSlots() {
        List<LocalDate> days = List.of(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 3));
        List<CampaignPlanner.SlotDef> slots = CampaignPlanner.slots(days, LocalTime.of(8, 0), LocalTime.of(16, 0), 20, 4, ZoneId.of("Europe/London"));
        // 2 distinct days × 24 slots × 4 stations
        assertThat(slots).hasSize(2 * 24 * 4);
        assertThat(slots.get(0).startsAt()).isEqualTo(Instant.parse("2026-11-03T08:00:00Z"));
        assertThat(slots.get(0).station()).isEqualTo(1);
        assertThat(slots.get(3).station()).isEqualTo(4);
        assertThat(slots.get(4).startsAt()).isEqualTo(Instant.parse("2026-11-03T08:20:00Z"));
        assertThat(slots.get(slots.size() - 1).endsAt()).isEqualTo(Instant.parse("2026-11-04T16:00:00Z"));
        assertThat(CampaignPlanner.slotsPerDay(LocalTime.of(8, 0), LocalTime.of(8, 50), 20)).isEqualTo(2);
        assertThat(CampaignPlanner.slotsPerDay(LocalTime.of(9, 0), LocalTime.of(8, 0), 20)).isZero();
        // a 500-person audiometry day: 10 stations × 8 h of 10-minute slots
        assertThat(CampaignPlanner.slots(List.of(LocalDate.of(2026, 11, 5)), LocalTime.of(8, 0), LocalTime.of(16, 30), 10, 10,
                ZoneId.of("Europe/London"))).hasSize(510);
    }

    @Test
    void capacityAndWhatIfMaths() {
        assertThat(CapacityMath.meanDuration(List.of(20, 30, 10))).isEqualTo(20.0);
        assertThat(CapacityMath.meanDuration(List.of())).isEqualTo(30.0);
        assertThat(CapacityMath.sessionCapacity(480, 20)).isEqualTo(24);
        assertThat(CapacityMath.sessionCapacity(50, 20)).isEqualTo(2);
        assertThat(CapacityMath.runRate(List.of(10, 20, 31))).isEqualTo(20);
        assertThat(CapacityMath.runRate(List.of())).isZero();
        // two extra 8 h sessions a week for 4 weeks at 20 minutes = 2 × 4 × 24
        assertThat(CapacityMath.extraSessionCapacity(2, 480, 20, 28)).isEqualTo(192);
        assertThat(CapacityMath.extraSessionCapacity(0, 480, 20, 28)).isZero();
        assertThat(CapacityMath.campaignDayCapacity(20)).isEqualTo(96);
        assertThat(CapacityMath.share(10, 3, 0)).isEqualTo(4);
        assertThat(CapacityMath.share(10, 3, 1)).isEqualTo(3);
        assertThat(CapacityMath.share(10, 3, 2)).isEqualTo(3);
        assertThat(CapacityMath.utilisationPct(12, 48)).isEqualTo(25.0);
        assertThat(CapacityMath.utilisationPct(1, 0)).isZero();
        assertThat(CapacityMath.pct(1, 3)).isEqualTo(33.3);
    }
}
