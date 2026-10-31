package com.wellnessz.e33.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comparison and trend data next to a new capture (E33-HLR-08, E33-F11, contract 14.7): previous results, the baseline (first
 * accepted capture of the category), reference thresholds and the trend series. Thresholds are screening reference values for
 * display only – the clinical rules (STS, ATS/ERS, fitness standards) stay in the owning modules.
 */
public final class Comparison {

    /** One accepted capture: when, values, and whether any quality flag was raised. */
    public record Point(Instant effectiveAt, Map<String, BigDecimal> values, String flag) {
    }

    public record Previous(Instant effectiveAt, Map<String, BigDecimal> values, String flag) {
    }

    public record Threshold(String key, BigDecimal value, String source) {
    }

    public record TrendPoint(LocalDate date, String key, BigDecimal value) {
    }

    public record Summary(List<Previous> previous, Previous baseline, List<Threshold> thresholds, List<TrendPoint> trend) {
    }

    private static final Map<String, List<Threshold>> THRESHOLDS = Map.of(
            "VITALS", List.of(new Threshold("systolic", new BigDecimal("140"), "ESH 2018 office BP grade 1"),
                    new Threshold("diastolic", new BigDecimal("90"), "ESH 2018 office BP grade 1")),
            "BODY_COMPOSITION", List.of(new Threshold("bmi", new BigDecimal("25"), "WHO BMI healthy range upper limit"),
                    new Threshold("bmi", new BigDecimal("30"), "WHO BMI obesity range")),
            "SPIROMETRY", List.of(new Threshold("fev1FvcRatio", new BigDecimal("0.70"), "GOLD fixed ratio"),
                    new Threshold("fev1PctPredicted", new BigDecimal("80"), "ATS/ERS % predicted (screening)")),
            "AUDIOMETRY", List.of(new Threshold("left.4000", new BigDecimal("25"), "Normal hearing limit dB HL"),
                    new Threshold("right.4000", new BigDecimal("25"), "Normal hearing limit dB HL"),
                    new Threshold("left.6000", new BigDecimal("25"), "Normal hearing limit dB HL"),
                    new Threshold("right.6000", new BigDecimal("25"), "Normal hearing limit dB HL")));

    /** The four families with trend views (E33-F11 acceptance). */
    public static final List<String> CATEGORIES = List.of("AUDIOMETRY", "SPIROMETRY", "VITALS", "BODY_COMPOSITION");

    private static final int PREVIOUS_LIMIT = 5;

    private Comparison() {
    }

    public static List<Threshold> thresholds(String category) {
        return THRESHOLDS.getOrDefault(category, List.of());
    }

    /**
     * @param points accepted captures of the category for the employee, any order
     */
    public static Summary build(String category, List<Point> points) {
        List<Point> sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparing(Point::effectiveAt));
        Previous baseline = sorted.isEmpty() ? null : prev(sorted.get(0));
        List<Previous> previous = new ArrayList<>();
        for (int i = sorted.size() - 1; i >= 0 && previous.size() < PREVIOUS_LIMIT; i--) {
            previous.add(prev(sorted.get(i)));
        }
        List<TrendPoint> trend = new ArrayList<>();
        for (Point p : sorted) {
            LocalDate d = LocalDate.ofInstant(p.effectiveAt(), ZoneOffset.UTC);
            p.values().forEach((k, v) -> trend.add(new TrendPoint(d, k, v)));
        }
        return new Summary(previous, baseline, thresholds(category), trend);
    }

    private static Previous prev(Point p) {
        return new Previous(p.effectiveAt(), new LinkedHashMap<>(p.values()), p.flag());
    }
}
