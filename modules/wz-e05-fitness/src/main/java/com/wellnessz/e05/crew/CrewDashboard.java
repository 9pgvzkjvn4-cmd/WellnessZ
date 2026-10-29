package com.wellnessz.e05.crew;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Aggregates the roster-status history into the CMO crew dashboard (E05-BL-013): unfit / limited crew by base with
 * average duration, by fleet, and a weekly trend. No identifiers leave this class and every count between 1 and k−1 is
 * suppressed (small-cell rule, k = 5).
 */
public final class CrewDashboard {

    private CrewDashboard() {
    }

    /** One roster-status message: the status holds from {@code at} until the employee's next message. */
    public record Status(String employeeId, String rosterStatus, Instant at) {
    }

    public record BaseCell(String base, Long count, Double avgDays, boolean suppressed) {
    }

    public record FleetCell(String fleet, Long unfit, Long limited, boolean suppressed) {
    }

    public record WeekCell(String week, Long unfit, Long limited, boolean suppressed) {
    }

    public record Result(LocalDate from, LocalDate to, List<BaseCell> unfitByBase, List<BaseCell> limitedByBase, List<FleetCell> byFleet,
                         List<WeekCell> trend, int minimumCellSize) {
    }

    private record Interval(String status, Instant from, Instant to) {
    }

    /**
     * @param history    messages ordered by employee then time (all history up to the end of the window)
     * @param attributes employee id → {base, fleet}
     */
    public static Result compute(List<Status> history, Map<String, String[]> attributes, LocalDate from, LocalDate to, int k) {
        Instant start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, List<Interval>> intervals = intervals(history, end);

        Map<String, List<Double>> unfitDaysByBase = new TreeMap<>();
        Map<String, List<Double>> limitedDaysByBase = new TreeMap<>();
        Map<String, long[]> fleet = new TreeMap<>();
        for (Map.Entry<String, List<Interval>> e : intervals.entrySet()) {
            String[] attr = attributes.getOrDefault(e.getKey(), new String[] {"UNKNOWN", "UNKNOWN"});
            double unfit = overlapDays(e.getValue(), "UNFIT", start, end);
            double limited = overlapDays(e.getValue(), "LIMITED", start, end);
            if (unfit > 0) {
                unfitDaysByBase.computeIfAbsent(attr[0], b -> new ArrayList<>()).add(unfit);
                fleet.computeIfAbsent(attr[1], f -> new long[2])[0]++;
            }
            if (limited > 0) {
                limitedDaysByBase.computeIfAbsent(attr[0], b -> new ArrayList<>()).add(limited);
                fleet.computeIfAbsent(attr[1], f -> new long[2])[1]++;
            }
        }
        List<FleetCell> byFleet = new ArrayList<>();
        fleet.forEach((f, c) -> {
            boolean s = small(c[0], k) || small(c[1], k);
            byFleet.add(new FleetCell(f, small(c[0], k) ? null : c[0], small(c[1], k) ? null : c[1], s));
        });
        return new Result(from, to, baseCells(unfitDaysByBase, k), baseCells(limitedDaysByBase, k), byFleet,
                trend(intervals, from, to, k), k);
    }

    static boolean small(long count, int k) {
        return count > 0 && count < k;
    }

    private static List<BaseCell> baseCells(Map<String, List<Double>> byBase, int k) {
        List<BaseCell> out = new ArrayList<>();
        byBase.forEach((base, days) -> {
            long n = days.size();
            if (small(n, k)) {
                out.add(new BaseCell(base, null, null, true));
            } else {
                double avg = days.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                out.add(new BaseCell(base, n, Math.round(avg * 10) / 10.0, false));
            }
        });
        return out;
    }

    private static Map<String, List<Interval>> intervals(List<Status> history, Instant end) {
        Map<String, List<Status>> byEmployee = new LinkedHashMap<>();
        for (Status s : history) {
            byEmployee.computeIfAbsent(s.employeeId(), x -> new ArrayList<>()).add(s);
        }
        Map<String, List<Interval>> out = new HashMap<>();
        byEmployee.forEach((id, list) -> {
            list.sort((a, b) -> a.at().compareTo(b.at()));
            List<Interval> iv = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                Instant next = i + 1 < list.size() ? list.get(i + 1).at() : end;
                iv.add(new Interval(list.get(i).rosterStatus(), list.get(i).at(), next.isAfter(end) ? end : next));
            }
            out.put(id, iv);
        });
        return out;
    }

    private static double overlapDays(List<Interval> intervals, String status, Instant start, Instant end) {
        long seconds = 0;
        for (Interval i : intervals) {
            if (!status.equals(i.status())) {
                continue;
            }
            Instant a = i.from().isAfter(start) ? i.from() : start;
            Instant b = i.to().isBefore(end) ? i.to() : end;
            if (b.isAfter(a)) {
                seconds += Duration.between(a, b).toSeconds();
            }
        }
        return seconds / 86400.0;
    }

    private static List<WeekCell> trend(Map<String, List<Interval>> intervals, LocalDate from, LocalDate to, int k) {
        List<WeekCell> out = new ArrayList<>();
        LocalDate week = from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        while (!week.isAfter(to)) {
            Instant a = week.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant b = week.plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Set<String> unfit = new HashSet<>();
            Set<String> limited = new HashSet<>();
            intervals.forEach((id, list) -> {
                if (overlapDays(list, "UNFIT", a, b) > 0) {
                    unfit.add(id);
                }
                if (overlapDays(list, "LIMITED", a, b) > 0) {
                    limited.add(id);
                }
            });
            long u = unfit.size();
            long l = limited.size();
            String label = week.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format(java.util.Locale.ROOT, "%02d",
                    week.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            out.add(new WeekCell(label, small(u, k) ? null : u, small(l, k) ? null : l, small(u, k) || small(l, k)));
            week = week.plusWeeks(1);
        }
        return out;
    }
}
