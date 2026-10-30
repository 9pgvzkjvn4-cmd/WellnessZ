package com.wellnessz.e10.pathway;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;
import java.util.random.RandomGenerator;

/**
 * Unannounced follow-up testing (E10-BL-008): {@code n} distinct dates drawn uniformly at random from the plan window
 * {@code (start, start + months]} – never on the start day itself, so a test cannot be anticipated from the plan.
 */
public final class FollowUpScheduler {

    private FollowUpScheduler() {
    }

    public static LocalDate windowEnd(LocalDate start, int months) {
        return start.plusMonths(months);
    }

    /** Sorted, distinct dates; capped at the number of days in the window. */
    public static List<LocalDate> schedule(LocalDate start, int months, int n, RandomGenerator random) {
        if (n <= 0 || months <= 0) {
            return List.of();
        }
        LocalDate end = windowEnd(start, months);
        int days = (int) ChronoUnit.DAYS.between(start, end);
        int count = Math.min(n, days);
        TreeSet<LocalDate> dates = new TreeSet<>();
        while (dates.size() < count) {
            dates.add(start.plusDays(1L + random.nextInt(days)));
        }
        return List.copyOf(dates);
    }
}
