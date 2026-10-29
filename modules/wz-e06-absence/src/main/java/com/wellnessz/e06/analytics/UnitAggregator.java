package com.wellnessz.e06.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.wellnessz.e06.model.AbsenceEnums;
import com.wellnessz.e06.rules.AbsencePatterns;
import com.wellnessz.e06.rules.RuleEngine;

/**
 * Pure unit-level absence aggregation (E06-F06) with small-group suppression: a unit whose headcount is below the minimum
 * group size is published as {@code {key, label, suppressed:true}} without any counts. Never person level.
 */
public final class UnitAggregator {

    private UnitAggregator() {
    }

    /** A member of the population: their unit key / label and their episodes (reason category only used for OH output). */
    public record Member(String employeeId, String key, String label, List<Absence> absences) {
    }

    public record Absence(RuleEngine.EpisodeFact episode, String reasonCategory) {
    }

    /** A published row; counts are null (omitted) when suppressed. */
    public record UnitRow(String key, String label, Integer headcount, Integer episodes, Long daysLost, BigDecimal absenceRatePct,
                          BigDecimal frequencyRate, BigDecimal avgDurationDays, BigDecimal bradfordMean, Map<String, Integer> categories,
                          boolean suppressed) {
    }

    public static List<UnitRow> aggregate(Collection<Member> members, LocalDate from, LocalDate to, int minGroupSize, boolean withCategories) {
        Map<String, List<Member>> byKey = new TreeMap<>();
        for (Member m : members) {
            byKey.computeIfAbsent(m.key() == null ? "UNKNOWN" : m.key(), k -> new ArrayList<>()).add(m);
        }
        List<UnitRow> out = new ArrayList<>();
        byKey.forEach((key, list) -> out.add(row(key, list.get(0).label(), list, from, to, minGroupSize, withCategories)));
        return out;
    }

    static UnitRow row(String key, String label, List<Member> list, LocalDate from, LocalDate to, int minGroupSize, boolean withCategories) {
        int headcount = list.size();
        if (headcount < minGroupSize) {
            return new UnitRow(key, label, null, null, null, null, null, null, null, null, true);
        }
        Stats s = stats(list, from, to);
        Map<String, Integer> categories = null;
        if (withCategories) {
            categories = new LinkedHashMap<>();
            for (AbsenceEnums.ReasonCategory c : AbsenceEnums.ReasonCategory.values()) {
                categories.put(c.name(), 0);
            }
            for (Member m : list) {
                for (Absence a : relevant(m, from, to)) {
                    String c = a.reasonCategory() == null ? AbsenceEnums.ReasonCategory.UNDISCLOSED.name() : a.reasonCategory();
                    categories.merge(c, 1, Integer::sum);
                }
            }
        }
        return new UnitRow(key, label, headcount, s.episodes(), s.daysLost(), s.absenceRatePct(), s.frequencyRate(), s.avgDurationDays(),
                s.bradfordMean(), categories, false);
    }

    public record Stats(int headcount, int episodes, long daysLost, BigDecimal absenceRatePct, BigDecimal frequencyRate, BigDecimal avgDurationDays,
                        BigDecimal bradfordMean) {
    }

    /** Statistics for a population over [from, to]: absence rate = days lost / (headcount × calendar days). */
    public static Stats stats(Collection<Member> list, LocalDate from, LocalDate to) {
        int headcount = list.size();
        int episodes = 0;
        long days = 0;
        long bradfordSum = 0;
        for (Member m : list) {
            List<Absence> rel = relevant(m, from, to);
            long d = rel.stream().mapToLong(a -> AbsencePatterns.daysWithin(a.episode(), from, to)).sum();
            episodes += rel.size();
            days += d;
            bradfordSum += AbsencePatterns.bradford(rel.size(), d);
        }
        long periodDays = ChronoUnit.DAYS.between(from, to) + 1;
        return new Stats(headcount, episodes, days, pct(days, (long) headcount * periodDays), ratio(episodes, headcount, 2), ratio(days, episodes, 1),
                ratio(bradfordSum, headcount, 1));
    }

    static List<Absence> relevant(Member m, LocalDate from, LocalDate to) {
        return m.absences().stream().filter(a -> a.episode().triggerable() && !a.episode().start().isAfter(to)
                && (a.episode().end() == null || !a.episode().end().isBefore(from))).toList();
    }

    public static BigDecimal pct(long part, long whole) {
        return whole <= 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }

    static BigDecimal ratio(long a, long b, int scale) {
        return b <= 0 ? null : BigDecimal.valueOf(a).divide(BigDecimal.valueOf(b), scale, RoundingMode.HALF_UP);
    }

    /** Median of the values, one decimal; null when empty. */
    public static BigDecimal median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> v = new ArrayList<>(values);
        v.sort(Double::compare);
        int n = v.size();
        double m = n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2.0;
        return BigDecimal.valueOf(m).setScale(1, RoundingMode.HALF_UP);
    }
}
