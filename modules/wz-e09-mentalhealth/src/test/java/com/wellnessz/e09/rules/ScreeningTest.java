package com.wellnessz.e09.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.wellnessz.e09.model.MentalHealthEnums.Band;
import com.wellnessz.e09.model.MentalHealthEnums.Instrument;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;

class ScreeningTest {

    @ParameterizedTest
    @CsvSource({"0,MINIMAL", "4,MINIMAL", "5,MILD", "9,MILD", "10,MODERATE", "14,MODERATE", "15,MODERATELY_SEVERE", "19,MODERATELY_SEVERE",
            "20,SEVERE", "27,SEVERE"})
    void phq9Bands(int score, Band band) {
        assertThat(Screening.band(Instrument.PHQ9, score)).isEqualTo(band);
    }

    @ParameterizedTest
    @CsvSource({"0,MINIMAL", "4,MINIMAL", "5,MILD", "9,MILD", "10,MODERATE", "14,MODERATE", "15,SEVERE", "21,SEVERE"})
    void gad7BandsHaveNoModeratelySevere(int score, Band band) {
        assertThat(Screening.band(Instrument.GAD7, score)).isEqualTo(band);
    }

    @Test
    void phq9ScoreSumsAnswersWithoutUrgency() {
        Screening.Result r = Screening.score(Instrument.PHQ9, List.of(1, 2, 1, 2, 1, 1, 2, 1, 0));
        assertThat(r.score()).isEqualTo(11);
        assertThat(r.band()).isEqualTo(Band.MODERATE);
        assertThat(r.urgent()).isFalse();
        assertThat(r.risk()).isEqualTo(Risk.MODERATE);
    }

    @Test
    void phq9Item9AboveZeroIsUrgentWhateverTheTotal() {
        Screening.Result r = Screening.score(Instrument.PHQ9, List.of(0, 0, 0, 0, 0, 0, 0, 0, 1));
        assertThat(r.score()).isEqualTo(1);
        assertThat(r.band()).isEqualTo(Band.MINIMAL);
        assertThat(r.urgent()).isTrue();
        assertThat(r.risk()).isEqualTo(Risk.URGENT);
    }

    @Test
    void severeBandIsUrgentForBothInstruments() {
        assertThat(Screening.score(Instrument.PHQ9, List.of(3, 3, 3, 3, 3, 3, 2, 0, 0)).urgent()).isTrue(); // 20, item 9 = 0
        Screening.Result gad = Screening.score(Instrument.GAD7, List.of(3, 3, 3, 2, 2, 1, 1));
        assertThat(gad.score()).isEqualTo(15);
        assertThat(gad.band()).isEqualTo(Band.SEVERE);
        assertThat(gad.urgent()).isTrue();
    }

    @Test
    void moderatelySevereWithoutItem9IsHighNotUrgent() {
        Screening.Result r = Screening.score(Instrument.PHQ9, List.of(2, 2, 2, 2, 2, 2, 2, 2, 0));
        assertThat(r.band()).isEqualTo(Band.MODERATELY_SEVERE);
        assertThat(r.urgent()).isFalse();
        assertThat(r.risk()).isEqualTo(Risk.HIGH);
    }

    @Test
    void gad7HasNoItem9Rule() {
        assertThat(Screening.score(Instrument.GAD7, List.of(0, 0, 0, 0, 0, 0, 3)).urgent()).isFalse();
    }

    @Test
    void invalidAnswersAreRejected() {
        assertThatThrownBy(() -> Screening.score(Instrument.PHQ9, List.of(1, 1, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Screening.score(Instrument.GAD7, List.of(0, 0, 0, 0, 0, 0, 4))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Screening.score(Instrument.GAD7, Arrays.asList(0, 0, 0, null, 0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Screening.score(Instrument.GAD7, List.of(0, 0, 0, -1, 0, 0, 0))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void urgentNextStepsNameTheEmergencyNumber() {
        Screening.Result r = Screening.score(Instrument.PHQ9, List.of(0, 0, 0, 0, 0, 0, 0, 0, 2));
        assertThat(Screening.nextSteps(r, "999")).anyMatch(s -> s.contains("999")).anyMatch(s -> s.contains("today"));
    }

    @Test
    void riskOnlyRises() {
        assertThat(Risk.MODERATE.max(Risk.LOW)).isEqualTo(Risk.MODERATE);
        assertThat(Risk.LOW.max(Risk.URGENT)).isEqualTo(Risk.URGENT);
        assertThat(Risk.HIGH.max(null)).isEqualTo(Risk.HIGH);
    }
}
