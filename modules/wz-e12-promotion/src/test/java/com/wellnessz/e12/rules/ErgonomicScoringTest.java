package com.wellnessz.e12.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.common.error.ApiException;

class ErgonomicScoringTest {

    static final List<ErgonomicScoring.Item> DSE = List.of(new ErgonomicScoring.Item("CHAIR", "Chair", 1),
            new ErgonomicScoring.Item("SCREEN_POSITION", "Screen", 1), new ErgonomicScoring.Item("KEYBOARD_MOUSE", "Keyboard", 1),
            new ErgonomicScoring.Item("DESK_SPACE", "Desk", 1), new ErgonomicScoring.Item("LIGHTING_GLARE", "Lighting", 1),
            new ErgonomicScoring.Item("BREAKS", "Breaks", 1), new ErgonomicScoring.Item("SCREEN_TIME", "Screen time", 2),
            new ErgonomicScoring.Item("POSTURE", "Posture", 2), new ErgonomicScoring.Item("DISCOMFORT", "Discomfort", 2));

    static Map<String, Integer> all(int score) {
        Map<String, Integer> m = new LinkedHashMap<>();
        DSE.forEach(i -> m.put(i.code(), score));
        return m;
    }

    @Test
    void allZeroIsGreenAndAllThreeIsRed() {
        assertThat(ErgonomicScoring.score(DSE, all(0), 30, 60)).isEqualTo(new ErgonomicScoring.Score(0, "GREEN"));
        assertThat(ErgonomicScoring.score(DSE, all(3), 30, 60)).isEqualTo(new ErgonomicScoring.Score(100, "RED"));
    }

    @Test
    void weightedScoreMatchesTheDemoWorkstation() {
        Map<String, Integer> m = new HashMap<>(Map.of("CHAIR", 2, "SCREEN_POSITION", 2, "KEYBOARD_MOUSE", 1, "DESK_SPACE", 0, "LIGHTING_GLARE", 1,
                "BREAKS", 1, "SCREEN_TIME", 2, "POSTURE", 1, "DISCOMFORT", 1));
        // (2+2+1+0+1+1 + 2*2 + 1*2 + 1*2) / 36 = 15 / 36 = 41.7 %
        assertThat(ErgonomicScoring.score(DSE, m, 30, 60)).isEqualTo(new ErgonomicScoring.Score(42, "AMBER"));
    }

    @Test
    void severeHeavilyWeightedItemLiftsGreenToAmber() {
        Map<String, Integer> m = all(0);
        m.put("POSTURE", 3);
        ErgonomicScoring.Score s = ErgonomicScoring.score(DSE, m, 30, 60);
        assertThat(s.riskScore()).isEqualTo(17);
        assertThat(s.rag()).isEqualTo("AMBER");
        Map<String, Integer> light = all(0);
        light.put("CHAIR", 3);
        assertThat(ErgonomicScoring.score(DSE, light, 30, 60).rag()).isEqualTo("GREEN");
    }

    @Test
    void bandBoundaries() {
        Map<String, Integer> m = all(1); // 12 / 36 = 33 %
        assertThat(ErgonomicScoring.score(DSE, m, 30, 60).rag()).isEqualTo("AMBER");
        assertThat(ErgonomicScoring.score(DSE, m, 34, 60).rag()).isEqualTo("GREEN");
        assertThat(ErgonomicScoring.score(DSE, all(2), 30, 67).rag()).isEqualTo("RED");
    }

    @Test
    void incompleteUnknownOrOutOfRangeChecklistsAreRejected() {
        Map<String, Integer> missing = all(1);
        missing.remove("BREAKS");
        assertThatThrownBy(() -> ErgonomicScoring.score(DSE, missing, 30, 60)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("checklist_incomplete");
        Map<String, Integer> unknown = all(1);
        unknown.put("COFFEE", 1);
        assertThatThrownBy(() -> ErgonomicScoring.score(DSE, unknown, 30, 60)).extracting(e -> ((ApiException) e).code())
                .isEqualTo("unknown_checklist_item");
        Map<String, Integer> high = all(1);
        high.put("CHAIR", 4);
        assertThatThrownBy(() -> ErgonomicScoring.score(DSE, high, 30, 60)).extracting(e -> ((ApiException) e).code())
                .isEqualTo("invalid_item_score");
    }
}
