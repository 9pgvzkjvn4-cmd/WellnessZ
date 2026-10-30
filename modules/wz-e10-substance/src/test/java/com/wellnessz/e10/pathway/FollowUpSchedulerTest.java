package com.wellnessz.e10.pathway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;

class FollowUpSchedulerTest {

    static final LocalDate START = LocalDate.of(2026, 10, 30);

    @Test
    void datesAreDistinctSortedAndInsideThePlanWindow() {
        for (long seed = 0; seed < 200; seed++) {
            List<LocalDate> dates = FollowUpScheduler.schedule(START, 12, 6, new Random(seed));
            assertThat(dates).hasSize(6).doesNotHaveDuplicates().isSorted();
            assertThat(dates).allSatisfy(d -> assertThat(d).isAfter(START).isBeforeOrEqualTo(START.plusMonths(12)));
        }
    }

    @Test
    void datesAreRandomisedAndUnannounced() {
        Set<List<LocalDate>> schedules = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            schedules.add(FollowUpScheduler.schedule(START, 12, 6, new Random(seed)));
        }
        assertThat(schedules).hasSizeGreaterThan(45);
        // spread over the whole window, not bunched at the start
        Set<Integer> months = new HashSet<>();
        for (long seed = 0; seed < 50; seed++) {
            FollowUpScheduler.schedule(START, 12, 6, new Random(seed)).forEach(d -> months.add(d.getMonthValue()));
        }
        assertThat(months).hasSize(12);
    }

    @Test
    void edgeCases() {
        assertThat(FollowUpScheduler.schedule(START, 12, 0, new Random(1))).isEmpty();
        assertThat(FollowUpScheduler.schedule(START, 0, 3, new Random(1))).isEmpty();
        assertThat(FollowUpScheduler.schedule(LocalDate.of(2026, 2, 1), 1, 40, new Random(1))).hasSize(28); // capped at days in window
        assertThat(FollowUpScheduler.windowEnd(START, 12)).isEqualTo(LocalDate.of(2027, 10, 30));
    }
}
