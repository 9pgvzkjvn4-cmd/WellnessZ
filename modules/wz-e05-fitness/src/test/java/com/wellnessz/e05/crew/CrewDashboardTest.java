package com.wellnessz.e05.crew;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CrewDashboardTest {

    static final LocalDate FROM = LocalDate.of(2026, 10, 5); // Monday
    static final LocalDate TO = LocalDate.of(2026, 10, 18);

    static Instant at(LocalDate d) {
        return d.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    @Test
    void countsDurationsAndSuppressesSmallCells() {
        List<CrewDashboard.Status> history = new ArrayList<>();
        Map<String, String[]> attrs = new LinkedHashMap<>();
        // six LHR A320 crew unfit for 4 days each (inside the window)
        for (int i = 0; i < 6; i++) {
            String id = "C" + i;
            history.add(new CrewDashboard.Status(id, "PENDING", at(FROM.minusDays(1))));
            history.add(new CrewDashboard.Status(id, "UNFIT", at(FROM.plusDays(1))));
            history.add(new CrewDashboard.Status(id, "FIT", at(FROM.plusDays(5))));
            attrs.put(id, new String[] {"LHR", "A320"});
        }
        // two MAN crew limited → suppressed (< 5)
        for (int i = 0; i < 2; i++) {
            String id = "M" + i;
            history.add(new CrewDashboard.Status(id, "LIMITED", at(FROM.minusDays(30))));
            attrs.put(id, new String[] {"MAN", "B787"});
        }
        CrewDashboard.Result r = CrewDashboard.compute(history, attrs, FROM, TO, 5);

        assertThat(r.unfitByBase()).singleElement().satisfies(c -> {
            assertThat(c.base()).isEqualTo("LHR");
            assertThat(c.count()).isEqualTo(6L);
            assertThat(c.avgDays()).isEqualTo(4.0);
            assertThat(c.suppressed()).isFalse();
        });
        assertThat(r.limitedByBase()).singleElement().satisfies(c -> {
            assertThat(c.base()).isEqualTo("MAN");
            assertThat(c.count()).isNull();
            assertThat(c.avgDays()).isNull();
            assertThat(c.suppressed()).isTrue();
        });
        assertThat(r.byFleet()).extracting(CrewDashboard.FleetCell::fleet).containsExactly("A320", "B787");
        assertThat(r.byFleet().get(0).unfit()).isEqualTo(6L);
        assertThat(r.byFleet().get(1).limited()).isNull();
        assertThat(r.trend()).hasSize(2);
        assertThat(r.trend().get(0).week()).isEqualTo("2026-W41");
        assertThat(r.trend().get(0).unfit()).isEqualTo(6L);
        assertThat(r.trend().get(1).unfit()).isZero();
        assertThat(r.trend().get(0).limited()).isNull(); // 2 limited crew → suppressed
        assertThat(r.toString()).doesNotContain("C0").doesNotContain("M1");
    }

    @Test
    void p95LatencyNearestRank() {
        assertThat(RosterFeedLog.p95(List.of())).isNull();
        List<Long> v = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            v.add(i);
        }
        assertThat(RosterFeedLog.p95(v)).isEqualTo(95L);
        assertThat(RosterFeedLog.p95(List.of(30L, 10L, 900L))).isEqualTo(900L);
    }
}
