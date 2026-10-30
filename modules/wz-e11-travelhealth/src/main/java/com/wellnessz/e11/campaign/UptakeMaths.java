package com.wellnessz.e11.campaign;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Campaign uptake (E11-F04, pure): uptake % = vaccinated / eligible; declined counts people who declined and were not
 * vaccinated. Groups with fewer eligible people than the minimum group size are published as {@code suppressed:true}
 * without counts.
 */
public final class UptakeMaths {

    private UptakeMaths() {
    }

    /** One eligible person in a group. */
    public record Person(String key, boolean vaccinated, boolean declined) {
    }

    /** Published row; counts are null when suppressed. */
    public record Row(String key, Integer eligible, Integer vaccinated, Integer declined, Double uptakePct, boolean suppressed) {
    }

    public static Double pct(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }

    public static Row row(String key, List<Person> people, int minGroupSize) {
        int eligible = people.size();
        if (eligible < minGroupSize) {
            return new Row(key, null, null, null, null, true);
        }
        int vaccinated = (int) people.stream().filter(Person::vaccinated).count();
        int declined = (int) people.stream().filter(p -> p.declined() && !p.vaccinated()).count();
        return new Row(key, eligible, vaccinated, declined, pct(vaccinated, eligible), false);
    }

    /** Rows per group key (sorted by key). */
    public static List<Row> rows(List<Person> people, int minGroupSize) {
        Map<String, List<Person>> groups = new TreeMap<>();
        for (Person p : people) {
            groups.computeIfAbsent(p.key() == null ? "UNKNOWN" : p.key(), k -> new ArrayList<>()).add(p);
        }
        List<Row> out = new ArrayList<>();
        groups.forEach((k, v) -> out.add(row(k, v, minGroupSize)));
        return out;
    }
}
