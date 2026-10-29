package com.wellnessz.e07.compliance;

import java.util.List;

/**
 * Compliance aggregation (E07-F08): compliance % = (enrolled − overdue) / enrolled; exempt people count as current. Groups
 * with fewer people than the minimum group size are published as {@code suppressed:true} without counts.
 */
public final class ComplianceMaths {

    private ComplianceMaths() {
    }

    public record Counts(int enrolled, int current, int due, int overdue) {
        public Counts add(String status) {
            return switch (status) {
                case "OVERDUE" -> new Counts(enrolled + 1, current, due, overdue + 1);
                case "DUE" -> new Counts(enrolled + 1, current, due + 1, overdue);
                default -> new Counts(enrolled + 1, current + 1, due, overdue);
            };
        }
    }

    public static final Counts ZERO = new Counts(0, 0, 0, 0);

    public record Row(String key, String label, Integer enrolled, Integer current, Integer due, Integer overdue, Double compliancePct,
                      boolean suppressed) {
    }

    public static Double pct(int enrolled, int overdue) {
        if (enrolled <= 0) {
            return null;
        }
        return Math.round((enrolled - overdue) * 1000.0 / enrolled) / 10.0;
    }

    public static Row row(String key, String label, Counts c, int minGroupSize) {
        if (c.enrolled() < minGroupSize) {
            return new Row(key, label, null, null, null, null, null, true);
        }
        return new Row(key, label, c.enrolled(), c.current(), c.due(), c.overdue(), pct(c.enrolled(), c.overdue()), false);
    }

    /** Overall compliance of published (non-suppressed) rows. */
    public static Double overall(List<Row> rows) {
        int enrolled = 0;
        int overdue = 0;
        for (Row r : rows) {
            if (!r.suppressed()) {
                enrolled += r.enrolled();
                overdue += r.overdue();
            }
        }
        return pct(enrolled, overdue);
    }
}
