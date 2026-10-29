package com.wellnessz.e06.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.e06.rules.RuleEngine;

class UnitAggregatorTest {

    static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    static final LocalDate TO = LocalDate.of(2026, 10, 10); // 10 days

    static UnitAggregator.Absence abs(LocalDate s, LocalDate e, String reason) {
        return new UnitAggregator.Absence(new RuleEngine.EpisodeFact(UUID.randomUUID(), "HRIS", s, e, "SICKNESS"), reason);
    }

    static List<UnitAggregator.Member> unit(String key, int headcount, List<List<UnitAggregator.Absence>> absences) {
        List<UnitAggregator.Member> out = new ArrayList<>();
        for (int i = 0; i < headcount; i++) {
            out.add(new UnitAggregator.Member(key + i, key, key + " unit", i < absences.size() ? absences.get(i) : List.of()));
        }
        return out;
    }

    @Test
    void smallUnitsAreSuppressedWithoutAnyCounts() {
        List<UnitAggregator.Member> members = new ArrayList<>(unit("BIG", 5, List.of(
                List.of(abs(FROM, FROM.plusDays(1), "MSK")), List.of(abs(FROM.plusDays(4), FROM.plusDays(4), null)))));
        members.addAll(unit("SMALL", 2, List.of(List.of(abs(FROM, TO, "MENTAL_HEALTH")))));
        List<UnitAggregator.UnitRow> rows = UnitAggregator.aggregate(members, FROM, TO, 5, true);
        assertThat(rows).extracting(UnitAggregator.UnitRow::key).containsExactly("BIG", "SMALL");
        UnitAggregator.UnitRow small = rows.get(1);
        assertThat(small.suppressed()).isTrue();
        assertThat(small.headcount()).isNull();
        assertThat(small.episodes()).isNull();
        assertThat(small.daysLost()).isNull();
        assertThat(small.categories()).isNull();
        UnitAggregator.UnitRow big = rows.get(0);
        assertThat(big.suppressed()).isFalse();
        assertThat(big.headcount()).isEqualTo(5);
        assertThat(big.episodes()).isEqualTo(2);
        assertThat(big.daysLost()).isEqualTo(3);
        assertThat(big.absenceRatePct()).isEqualByComparingTo(new BigDecimal("6.0")); // 3 / (5 × 10)
        assertThat(big.frequencyRate()).isEqualByComparingTo(new BigDecimal("0.40"));
        assertThat(big.avgDurationDays()).isEqualByComparingTo(new BigDecimal("1.5"));
        assertThat(big.bradfordMean()).isEqualByComparingTo(new BigDecimal("0.6")); // (2 + 1) / 5
        assertThat(big.categories()).containsEntry("MSK", 1).containsEntry("UNDISCLOSED", 1).containsEntry("MENTAL_HEALTH", 0);
        // without OH rights no categories at all
        assertThat(UnitAggregator.aggregate(members, FROM, TO, 5, false).get(0).categories()).isNull();
    }

    @Test
    void medianAndPercentages() {
        assertThat(UnitAggregator.median(List.of())).isNull();
        assertThat(UnitAggregator.median(List.of(3.0, 1.0, 2.0))).isEqualByComparingTo("2.0");
        assertThat(UnitAggregator.median(List.of(1.0, 4.0))).isEqualByComparingTo("2.5");
        assertThat(UnitAggregator.pct(19, 20)).isEqualByComparingTo("95.0");
        assertThat(UnitAggregator.pct(1, 0)).isNull();
    }
}
