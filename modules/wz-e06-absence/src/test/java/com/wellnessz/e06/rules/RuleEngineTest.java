package com.wellnessz.e06.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;

class RuleEngineTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 29); // a Thursday

    static RuleEngine.Rule rule(String code, String sub, String type, Map<String, Object> params, String action) {
        return new RuleEngine.Rule(UUID.randomUUID(), code, code, sub, type, params, action);
    }

    static final RuleEngine.Rule DURATION_10D = rule("DURATION_10D", null, "DURATION", Map.of("minDays", 11), "EARLY_INTERVENTION");
    static final RuleEngine.Rule FREQ = rule("FREQ_3_IN_90", null, "FREQUENCY", Map.of("episodes", 3, "windowDays", 90), "EARLY_INTERVENTION");
    static final RuleEngine.Rule MON_FRI = rule("MON_FRI_PATTERN", null, "PATTERN",
            Map.of("pattern", "MONDAY_FRIDAY", "minOccurrences", 3, "windowDays", 180), "EARLY_INTERVENTION");
    static final RuleEngine.Rule LTA = rule("LTA_28D", null, "DURATION", Map.of("minDays", 28), "LONG_TERM");
    static final RuleEngine.Rule SAFETY = rule("SAFETY_CRITICAL_3D", null, "SAFETY_CRITICAL", Map.of("minDays", 4), "EARLY_INTERVENTION");
    static final RuleEngine.Rule POST_INCIDENT = rule("POST_INCIDENT_7D", null, "POST_INCIDENT", Map.of("withinDays", 7), "EARLY_INTERVENTION");
    static final RuleEngine.Rule CREW = rule("CREW_SICKNESS", null, "CREW_SICKNESS", Map.of("minDays", 8, "episodes", 3, "windowDays", 90), "AME_REVIEW");
    static final RuleEngine.Rule CAT_2D = rule("CAT_DURATION_2D", "CAT", "DURATION", Map.of("minDays", 3), "EARLY_INTERVENTION");
    static final List<RuleEngine.Rule> DEFAULTS = List.of(DURATION_10D, FREQ, MON_FRI, LTA, SAFETY, POST_INCIDENT, CREW, CAT_2D);

    static RuleEngine.EpisodeFact ep(LocalDate start, LocalDate end) {
        return ep(start, end, "SICKNESS", "HRIS");
    }

    static RuleEngine.EpisodeFact ep(LocalDate start, LocalDate end, String type, String source) {
        return new RuleEngine.EpisodeFact(UUID.randomUUID(), source, start, end, type);
    }

    static RuleEngine.Facts facts(String sub, boolean safety, boolean crew, List<LocalDate> incidents, RuleEngine.EpisodeFact... eps) {
        return new RuleEngine.Facts("E1", sub, safety, crew, List.of(eps), incidents);
    }

    static List<String> codes(List<RuleEngine.Match> m) {
        return m.stream().map(x -> x.rule().code()).toList();
    }

    @Test
    void durationCountsCalendarDaysInclusiveAndUsesMinimumDays() {
        RuleEngine.EpisodeFact ten = ep(TODAY.minusDays(9), null);
        assertThat(ten.days(TODAY)).isEqualTo(10);
        assertThat(RuleEngine.evaluate(DURATION_10D, facts("GND", false, false, List.of(), ten), TODAY)).isEmpty();
        RuleEngine.EpisodeFact eleven = ep(TODAY.minusDays(10), null);
        assertThat(RuleEngine.evaluate(DURATION_10D, facts("GND", false, false, List.of(), eleven), TODAY)).get()
                .satisfies(m -> assertThat(m.detail()).contains("11 days"));
        // an old closed episode does not raise stale triggers
        RuleEngine.EpisodeFact old = ep(TODAY.minusDays(200), TODAY.minusDays(150));
        assertThat(RuleEngine.evaluate(LTA, facts("GND", false, false, List.of(), old), TODAY)).isEmpty();
        assertThat(RuleEngine.evaluate(LTA, facts("GND", false, false, List.of(), ep(TODAY.minusDays(27), null)), TODAY)).isPresent();
        // future episodes count zero days; maternity-related and authorised absence never trigger
        assertThat(ep(TODAY.plusDays(2), null).days(TODAY)).isZero();
        assertThat(RuleEngine.evaluate(LTA, facts("GND", false, false, List.of(), ep(TODAY.minusDays(60), null, "MATERNITY_RELATED", "HRIS")), TODAY))
                .isEmpty();
    }

    @Test
    void frequencyNeedsTheEpisodesInsideTheWindow() {
        RuleEngine.Facts two = facts("GND", false, false, List.of(), ep(TODAY.minusDays(80), TODAY.minusDays(79)), ep(TODAY.minusDays(5), TODAY.minusDays(4)));
        assertThat(RuleEngine.evaluate(FREQ, two, TODAY)).isEmpty();
        RuleEngine.Facts three = facts("GND", false, false, List.of(), ep(TODAY.minusDays(89), TODAY.minusDays(88)),
                ep(TODAY.minusDays(40), TODAY.minusDays(40)), ep(TODAY.minusDays(5), TODAY.minusDays(4)));
        assertThat(RuleEngine.evaluate(FREQ, three, TODAY)).get().satisfies(m -> assertThat(m.detail()).startsWith("3 absences within 90 days"));
        RuleEngine.Facts outside = facts("GND", false, false, List.of(), ep(TODAY.minusDays(95), TODAY.minusDays(94)),
                ep(TODAY.minusDays(40), TODAY.minusDays(40)), ep(TODAY.minusDays(5), TODAY.minusDays(4)));
        assertThat(RuleEngine.evaluate(FREQ, outside, TODAY)).isEmpty();
    }

    @Test
    void mondayFridayPattern() {
        LocalDate monday = TODAY.with(TemporalAdjusters.previous(DayOfWeek.MONDAY));
        LocalDate friday = TODAY.with(TemporalAdjusters.previous(DayOfWeek.FRIDAY));
        LocalDate wednesday = TODAY.with(TemporalAdjusters.previous(DayOfWeek.WEDNESDAY));
        assertThat(RuleEngine.weekendAdjacent(ep(monday, monday))).isTrue();
        assertThat(RuleEngine.weekendAdjacent(ep(friday, friday))).isTrue();
        assertThat(RuleEngine.weekendAdjacent(ep(wednesday, wednesday.plusDays(2)))).isTrue(); // ends on Friday
        assertThat(RuleEngine.weekendAdjacent(ep(wednesday, wednesday))).isFalse();
        assertThat(RuleEngine.weekendAdjacent(ep(wednesday.minusDays(1), wednesday.plusDays(1)))).isFalse(); // Tue–Thu
        List<RuleEngine.EpisodeFact> eps = new ArrayList<>(List.of(ep(monday.minusWeeks(20), monday.minusWeeks(20)),
                ep(friday.minusWeeks(10), friday.minusWeeks(10)), ep(wednesday.minusWeeks(3), wednesday.minusWeeks(3))));
        assertThat(RuleEngine.evaluate(MON_FRI, facts("GND", false, false, List.of(), eps.toArray(RuleEngine.EpisodeFact[]::new)), TODAY)).isEmpty();
        eps.add(ep(monday, monday.plusDays(1)));
        assertThat(RuleEngine.evaluate(MON_FRI, facts("GND", false, false, List.of(), eps.toArray(RuleEngine.EpisodeFact[]::new)), TODAY)).get()
                .satisfies(m -> assertThat(m.detail()).startsWith("3 Monday / Friday absences"));
        // outside the 180-day window
        List<RuleEngine.EpisodeFact> old = List.of(ep(monday.minusWeeks(30), monday.minusWeeks(30)), ep(friday.minusWeeks(28), friday.minusWeeks(28)),
                ep(monday, monday));
        assertThat(RuleEngine.evaluate(MON_FRI, facts("GND", false, false, List.of(), old.toArray(RuleEngine.EpisodeFact[]::new)), TODAY)).isEmpty();
    }

    @Test
    void safetyCriticalAndPostIncidentRules() {
        RuleEngine.EpisodeFact five = ep(TODAY.minusDays(4), null);
        assertThat(RuleEngine.evaluate(SAFETY, facts("GND", false, false, List.of(), five), TODAY)).isEmpty();
        assertThat(RuleEngine.evaluate(SAFETY, facts("GND", true, false, List.of(), five), TODAY)).isPresent();
        assertThat(RuleEngine.evaluate(SAFETY, facts("GND", true, false, List.of(), ep(TODAY.minusDays(2), null)), TODAY)).isEmpty();
        assertThat(RuleEngine.evaluate(POST_INCIDENT, facts("GND", false, false, List.of(TODAY.minusDays(10)), ep(TODAY.minusDays(4), null)), TODAY))
                .get().satisfies(m -> assertThat(m.detail()).contains("6 days after a workplace incident"));
        assertThat(RuleEngine.evaluate(POST_INCIDENT, facts("GND", false, false, List.of(TODAY.minusDays(20)), ep(TODAY.minusDays(4), null)), TODAY))
                .isEmpty();
        assertThat(RuleEngine.evaluate(POST_INCIDENT, facts("GND", false, false, List.of(), ep(TODAY.minusDays(4), null)), TODAY)).isEmpty();
    }

    @Test
    void crewSicknessByDurationOrFrequencyRaisesAmeReview() {
        assertThat(RuleEngine.evaluate(CREW, facts("CAB", false, false, List.of(), ep(TODAY.minusDays(9), null)), TODAY)).isEmpty();
        assertThat(RuleEngine.evaluate(CREW, facts("CAB", false, true, List.of(), ep(TODAY.minusDays(9), null)), TODAY)).get()
                .satisfies(m -> assertThat(m.detail()).startsWith("Crew sickness"));
        // a crew-system episode marks the employee as crew
        assertThat(RuleEngine.evaluate(CREW, facts("CAB", false, false, List.of(), ep(TODAY.minusDays(9), null, "SICKNESS", "CREW")), TODAY)).isPresent();
        assertThat(RuleEngine.evaluate(CREW, facts("CAB", false, true, List.of(), ep(TODAY.minusDays(60), TODAY.minusDays(59)),
                ep(TODAY.minusDays(30), TODAY.minusDays(30)), ep(TODAY.minusDays(1), null)), TODAY)).isPresent();
        assertThat(RuleEngine.evaluate(CREW, facts("CAB", false, true, List.of(), ep(TODAY.minusDays(2), null)), TODAY)).isEmpty();
    }

    @Test
    void subsidiaryRuleOverridesDefaultOfSameTypeAndActionAndOneMatchPerAction() {
        List<RuleEngine.Rule> cat = RuleEngine.effective(DEFAULTS, "CAT");
        assertThat(codes(cat.stream().map(r -> new RuleEngine.Match(r, null, "")).toList())).contains("CAT_DURATION_2D", "LTA_28D")
                .doesNotContain("DURATION_10D");
        assertThat(cat.get(0).code()).isEqualTo("CAT_DURATION_2D");
        assertThat(RuleEngine.effective(DEFAULTS, "GND").stream().map(RuleEngine.Rule::code)).contains("DURATION_10D").doesNotContain("CAT_DURATION_2D");
        // catering food handler, 30 days absent: early intervention (catering rule) + long term, nothing else
        List<RuleEngine.Match> m = RuleEngine.evaluateAll(DEFAULTS, facts("CAT", true, false, List.of(), ep(TODAY.minusDays(29), null)), TODAY);
        assertThat(codes(m)).containsExactly("CAT_DURATION_2D", "LTA_28D");
        assertThat(RuleEngine.evaluateAll(DEFAULTS, facts("CAT", true, false, List.of(), ep(TODAY.minusDays(1), null)), TODAY)).isEmpty();
    }

    @Test
    void parameterValidation() {
        assertThat(RuleEngine.validateParams("DURATION", Map.of("minDays", 5))).containsEntry("minDays", 5);
        assertThat(RuleEngine.validateParams("PATTERN", Map.of("minOccurrences", 3, "windowDays", 180))).containsEntry("pattern", "MONDAY_FRIDAY");
        assertThatThrownBy(() -> RuleEngine.validateParams("DURATION", Map.of("minDays", 0))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleEngine.validateParams("DURATION", Map.of("minDays", 2.5))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleEngine.validateParams("FREQUENCY", Map.of("episodes", 3))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleEngine.validateParams("DURATION", Map.of("minDays", 3, "extra", 1))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleEngine.validateParams("PATTERN", Map.of("pattern", "TUESDAY", "minOccurrences", 3, "windowDays", 180)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> RuleEngine.validateParams("DURATION", Map.of("minDays", "7"))).isInstanceOf(ApiException.class);
    }
}
