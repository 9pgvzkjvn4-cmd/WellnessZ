package com.wellnessz.e05.restriction;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    static final MatchingEngine.Rule LIFT_10 = new MatchingEngine.Rule("LIFT_MAX_KG", "LIFT_KG", BigDecimal.TEN, "No lifting above 10 kg");
    static final MatchingEngine.Rule NO_NIGHT = new MatchingEngine.Rule("NO_NIGHT_DUTY", "NIGHT", null, "No night duty");
    static final MatchingEngine.Rule NO_KNIFE = new MatchingEngine.Rule("NO_KNIFE_WORK", "KNIFE", null, "No knife or blade work");

    static MatchingEngine.Profile profile(String code, String unit, Map<String, Object> demands) {
        return new MatchingEngine.Profile(code, code, unit, demands);
    }

    @Test
    void numericRestrictionConflictsOnlyWhenDemandExceedsLimit() {
        assertThat(MatchingEngine.conflicts(LIFT_10, 23)).isTrue();
        assertThat(MatchingEngine.conflicts(LIFT_10, 10)).isFalse();
        assertThat(MatchingEngine.conflicts(LIFT_10, 5.5)).isFalse();
        assertThat(MatchingEngine.conflicts(LIFT_10, null)).isFalse();
    }

    @Test
    void booleanRestrictionConflictsWhenJobDemandsIt() {
        assertThat(MatchingEngine.conflicts(NO_NIGHT, true)).isTrue();
        assertThat(MatchingEngine.conflicts(NO_NIGHT, false)).isFalse();
        assertThat(MatchingEngine.conflicts(NO_NIGHT, null)).isFalse();
    }

    @Test
    void rankingPutsCompatibleSameDepartmentFirstThenFewestConflicts() {
        var ramp = profile("RAMP_AGENT", "GND", Map.of("LIFT_KG", 32, "NIGHT", true, "KNIFE", false));
        var packing = profile("CATERING_PACKING", "CAT", Map.of("LIFT_KG", 5, "NIGHT", false, "KNIFE", false));
        var office = profile("OFFICE_ADMIN", "CORP", Map.of("LIFT_KG", 5, "NIGHT", false, "KNIFE", false));
        var coldRoom = profile("CATERING_COLD_ROOM", "CAT", Map.of("LIFT_KG", 20, "NIGHT", true, "KNIFE", false));
        var assoc = profile("CATERING_ASSOC", "CAT", Map.of("LIFT_KG", 20, "NIGHT", true, "KNIFE", true));

        List<MatchingEngine.Match> ranked = MatchingEngine.rank(List.of(LIFT_10, NO_NIGHT, NO_KNIFE),
                List.of(ramp, office, assoc, coldRoom, packing), "CAT");

        assertThat(ranked).extracting(MatchingEngine.Match::jobCode)
                .containsExactly("CATERING_PACKING", "OFFICE_ADMIN", "CATERING_COLD_ROOM", "CATERING_ASSOC", "RAMP_AGENT");
        MatchingEngine.Match first = ranked.get(0);
        assertThat(first.compatible()).isTrue();
        assertThat(first.sameDepartment()).isTrue();
        assertThat(first.score()).isEqualTo(100);
        MatchingEngine.Match assocMatch = ranked.get(3);
        assertThat(assocMatch.compatible()).isFalse();
        assertThat(assocMatch.conflicts()).extracting(MatchingEngine.Conflict::dimension).containsExactly("LIFT_KG", "NIGHT", "KNIFE");
        assertThat(assocMatch.conflicts().get(0).demand()).isEqualTo(20);
        assertThat(assocMatch.conflicts().get(0).restriction()).isEqualTo("No lifting above 10 kg");
        assertThat(assocMatch.score()).isZero();
    }
}
