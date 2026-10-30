package com.wellnessz.e09.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.wellnessz.e09.model.MentalHealthEnums.Rag;

class PsychosocialScoringTest {

    @ParameterizedTest
    @CsvSource({"1,1,1,GREEN", "2,3,6,GREEN", "2,4,8,AMBER", "3,4,12,AMBER", "3,5,15,RED", "4,4,16,RED", "5,5,25,RED", "1,5,5,GREEN"})
    void scoreIsLikelihoodTimesSeverityWithRag(int likelihood, int severity, int score, Rag rag) {
        assertThat(PsychosocialScoring.score(likelihood, severity)).isEqualTo(score);
        assertThat(PsychosocialScoring.rag(score)).isEqualTo(rag);
    }

    @Test
    void overallIsTheWorstFactor() {
        assertThat(PsychosocialScoring.overall(List.of(4, 16, 9))).isEqualTo(16);
        assertThat(PsychosocialScoring.rag(PsychosocialScoring.overall(List.of(4, 16, 9)))).isEqualTo(Rag.RED);
        assertThat(PsychosocialScoring.overall(List.of())).isZero();
    }

    @Test
    void outOfRangeInputsAreRejected() {
        assertThatThrownBy(() -> PsychosocialScoring.score(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PsychosocialScoring.score(3, 6)).isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ small-cell suppression

    @Test
    void smallCellsAreSuppressedButZeroIsNot() {
        assertThat(Suppression.suppressed(0, 5)).isFalse();
        assertThat(Suppression.suppressed(1, 5)).isTrue();
        assertThat(Suppression.suppressed(4, 5)).isTrue();
        assertThat(Suppression.suppressed(5, 5)).isFalse();
        assertThat(Suppression.publish(3, 5)).isNull();
        assertThat(Suppression.publish(6, 5)).isEqualTo(6L);
        assertThat(Suppression.publish(0, 5)).isZero();
    }

    @Test
    void ratesAreWithheldForSmallPopulationsOrSmallNumerators() {
        assertThat(Suppression.ratePer100(6, 120, 5)).isEqualByComparingTo(new BigDecimal("5.0"));
        assertThat(Suppression.ratePer100(0, 120, 5)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Suppression.ratePer100(2, 120, 5)).isNull();
        assertThat(Suppression.ratePer100(0, 3, 5)).isNull();
        assertThat(Suppression.ratePer100(7, 0, 5)).isNull();
        assertThat(Suppression.ratePer100(5, 30, 5)).isEqualByComparingTo(new BigDecimal("16.7"));
    }

    // ------------------------------------------------------------------ calendar helpers

    @Test
    void workingDaysSkipWeekendsAndQuartersParse() {
        // Friday 2026-10-30 + 5 working days = Friday 2026-11-06
        assertThat(Calendar.plusWorkingDays(LocalDate.of(2026, 10, 30), 5)).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(Calendar.plusWorkingDays(LocalDate.of(2026, 10, 31), 1)).isEqualTo(LocalDate.of(2026, 11, 2));
        Calendar.Quarter q = Calendar.quarter("2026-Q3");
        assertThat(q.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(q.to()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(Calendar.quarterOf(LocalDate.of(2026, 12, 31))).isEqualTo("2026-Q4");
        assertThatThrownBy(() -> Calendar.quarter("2026-Q5")).isInstanceOf(IllegalArgumentException.class);
    }
}
