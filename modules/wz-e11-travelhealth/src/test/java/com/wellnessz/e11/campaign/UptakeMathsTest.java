package com.wellnessz.e11.campaign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wellnessz.e11.campaign.UptakeMaths.Person;
import com.wellnessz.e11.campaign.UptakeMaths.Row;

class UptakeMathsTest {

    static List<Person> group(String key, int vaccinated, int declinedOnly, int other) {
        List<Person> out = new ArrayList<>();
        for (int i = 0; i < vaccinated; i++) {
            out.add(new Person(key, true, false));
        }
        for (int i = 0; i < declinedOnly; i++) {
            out.add(new Person(key, false, true));
        }
        for (int i = 0; i < other; i++) {
            out.add(new Person(key, false, false));
        }
        return out;
    }

    @Test
    void percentages() {
        assertThat(UptakeMaths.pct(2, 3)).isEqualTo(66.7);
        assertThat(UptakeMaths.pct(0, 5)).isEqualTo(0.0);
        assertThat(UptakeMaths.pct(5, 5)).isEqualTo(100.0);
        assertThat(UptakeMaths.pct(1, 0)).isNull();
    }

    @Test
    void groupsBelowTheMinimumAreSuppressedWithoutCounts() {
        List<Person> people = new ArrayList<>(group("CAT", 3, 1, 2));
        people.addAll(group("FLT", 1, 0, 1));
        List<Row> rows = UptakeMaths.rows(people, 5);
        assertThat(rows).containsExactly(new Row("CAT", 6, 3, 1, 50.0, false), new Row("FLT", null, null, null, null, true));
    }

    @Test
    void aVaccinatedPersonWhoOnceDeclinedIsNotCountedAsDeclined() {
        List<Person> people = List.of(new Person("GND", true, true), new Person("GND", false, true), new Person("GND", false, false));
        assertThat(UptakeMaths.row("GND", people, 3)).isEqualTo(new Row("GND", 3, 1, 1, 33.3, false));
    }

    @Test
    void missingKeysAreGroupedAsUnknown() {
        assertThat(UptakeMaths.rows(List.of(new Person(null, true, false)), 1)).singleElement()
                .isEqualTo(new Row("UNKNOWN", 1, 1, 0, 100.0, false));
    }
}
