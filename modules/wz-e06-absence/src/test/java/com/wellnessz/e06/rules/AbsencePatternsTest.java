package com.wellnessz.e06.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AbsencePatternsTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29); // Thursday

    static RuleEngine.EpisodeFact ep(LocalDate s, LocalDate e) {
        return new RuleEngine.EpisodeFact(UUID.randomUUID(), "HRIS", s, e, "SICKNESS");
    }

    @Test
    void bradfordFactorIsSpellsSquaredTimesDays() {
        // classic examples: 1 spell of 10 days = 10; 10 spells of 1 day = 1000; 5 spells of 2 days = 250
        assertThat(AbsencePatterns.bradford(1, 10)).isEqualTo(10);
        assertThat(AbsencePatterns.bradford(10, 10)).isEqualTo(1000);
        assertThat(AbsencePatterns.bradford(5, 10)).isEqualTo(250);
        List<RuleEngine.EpisodeFact> spells = List.of(ep(TODAY.minusDays(300), TODAY.minusDays(299)), ep(TODAY.minusDays(100), TODAY.minusDays(100)),
                ep(TODAY.minusDays(2), null), // open: counts up to today (3 days)
                ep(TODAY.minusDays(400), TODAY.minusDays(390))); // outside 52 weeks
        assertThat(AbsencePatterns.bradford(spells, TODAY)).isEqualTo(3L * 3 * (2 + 1 + 3));
        assertThat(AbsencePatterns.bradford(List.of(), TODAY)).isZero();
        // maternity-related absence is not counted
        assertThat(AbsencePatterns.bradford(List.of(new RuleEngine.EpisodeFact(UUID.randomUUID(), "HRIS", TODAY.minusDays(30), null,
                "MATERNITY_RELATED")), TODAY)).isZero();
    }

    @Test
    void daysWithinClipsToTheWindow() {
        RuleEngine.EpisodeFact e = ep(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 5));
        assertThat(AbsencePatterns.daysWithin(e, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))).isEqualTo(5);
        assertThat(AbsencePatterns.daysWithin(e, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30))).isZero();
        assertThat(AbsencePatterns.daysWithin(ep(LocalDate.of(2026, 10, 20), null), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .isEqualTo(12);
    }

    @Test
    void summaryPatterns() {
        LocalDate mon = LocalDate.of(2026, 10, 26);
        List<RuleEngine.EpisodeFact> monFri = List.of(ep(mon, mon), ep(mon.minusDays(17), mon.minusDays(17)), ep(mon.minusWeeks(6), mon.minusWeeks(6)));
        assertThat(AbsencePatterns.patterns(monFri, TODAY, Set.of())).contains("MONDAY_FRIDAY", "FREQUENT_SHORT");
        // first working day after a bank holiday (Monday 31 Aug 2026 → Tuesday; Friday 3 Apr 2026 → Monday 6 Apr is itself a holiday)
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 5, 25));
        List<RuleEngine.EpisodeFact> post = List.of(ep(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)),
                ep(LocalDate.of(2026, 5, 26), LocalDate.of(2026, 5, 28)));
        assertThat(AbsencePatterns.patterns(post, TODAY, holidays)).containsExactly("POST_HOLIDAY");
        assertThat(AbsencePatterns.followsHoliday(LocalDate.of(2026, 12, 28), Set.of(LocalDate.of(2026, 12, 25)))).isTrue(); // Mon after Fri
        assertThat(AbsencePatterns.followsHoliday(LocalDate.of(2026, 12, 30), Set.of(LocalDate.of(2026, 12, 25)))).isFalse();
        assertThat(AbsencePatterns.patterns(List.of(ep(TODAY.minusDays(20), TODAY.minusDays(16))), TODAY, holidays)).isEmpty();
    }
}
