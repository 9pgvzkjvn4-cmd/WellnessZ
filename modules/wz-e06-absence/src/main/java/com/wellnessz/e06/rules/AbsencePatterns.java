package com.wellnessz.e06.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Pure absence statistics: Bradford factor (S² × D over a rolling 52 weeks), days lost and the patterns shown on the OH /
 * HR absence summary (MONDAY_FRIDAY, POST_HOLIDAY, FREQUENT_SHORT).
 */
public final class AbsencePatterns {

    public static final int WINDOW_DAYS = 365;
    static final int MONDAY_FRIDAY_MIN = 3;
    static final int MONDAY_FRIDAY_WINDOW = 180;
    static final int POST_HOLIDAY_MIN = 2;
    static final int FREQUENT_SHORT_MIN = 3;
    static final int SHORT_MAX_DAYS = 3;

    private AbsencePatterns() {
    }

    /** Episodes starting in the 365 days up to and including {@code asOf} (sickness / injury only). */
    public static List<RuleEngine.EpisodeFact> lastYear(Collection<RuleEngine.EpisodeFact> episodes, LocalDate asOf) {
        return episodes.stream().filter(RuleEngine.EpisodeFact::triggerable)
                .filter(e -> !e.start().isAfter(asOf) && e.start().isAfter(asOf.minusDays(WINDOW_DAYS))).toList();
    }

    /** Days absent between {@code from} and {@code to} (inclusive) – open episodes count up to {@code to}. */
    public static long daysWithin(RuleEngine.EpisodeFact e, LocalDate from, LocalDate to) {
        LocalDate s = e.start().isBefore(from) ? from : e.start();
        LocalDate end = e.end() == null || e.end().isAfter(to) ? to : e.end();
        return end.isBefore(s) ? 0 : ChronoUnit.DAYS.between(s, end) + 1;
    }

    /** Bradford factor B = S² × D: S spells and D days of sickness absence starting within the last 52 weeks. */
    public static long bradford(Collection<RuleEngine.EpisodeFact> episodes, LocalDate asOf) {
        List<RuleEngine.EpisodeFact> spells = lastYear(episodes, asOf);
        long days = spells.stream().mapToLong(e -> e.days(asOf)).sum();
        return (long) spells.size() * spells.size() * days;
    }

    public static long bradford(int spells, long days) {
        return (long) spells * spells * days;
    }

    /** Pattern flags for the absence summary. */
    public static List<String> patterns(Collection<RuleEngine.EpisodeFact> episodes, LocalDate asOf, Set<LocalDate> publicHolidays) {
        List<RuleEngine.EpisodeFact> year = lastYear(episodes, asOf);
        List<String> out = new ArrayList<>();
        long monFri = year.stream().filter(e -> e.start().isAfter(asOf.minusDays(MONDAY_FRIDAY_WINDOW)))
                .filter(RuleEngine::weekendAdjacent).count();
        if (monFri >= MONDAY_FRIDAY_MIN) {
            out.add("MONDAY_FRIDAY");
        }
        long postHoliday = year.stream().filter(e -> followsHoliday(e.start(), publicHolidays)).count();
        if (postHoliday >= POST_HOLIDAY_MIN) {
            out.add("POST_HOLIDAY");
        }
        long shortSpells = year.stream().filter(e -> e.end() != null && e.days(asOf) <= SHORT_MAX_DAYS).count();
        if (shortSpells >= FREQUENT_SHORT_MIN) {
            out.add("FREQUENT_SHORT");
        }
        return out;
    }

    /** The absence starts on the first working day after a public holiday (weekends in between are skipped). */
    static boolean followsHoliday(LocalDate start, Set<LocalDate> holidays) {
        if (holidays == null || holidays.isEmpty()) {
            return false;
        }
        LocalDate d = start.minusDays(1);
        for (int i = 0; i < 4; i++) {
            if (holidays.contains(d)) {
                return true;
            }
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                return false;
            }
            d = d.minusDays(1);
        }
        return false;
    }
}
