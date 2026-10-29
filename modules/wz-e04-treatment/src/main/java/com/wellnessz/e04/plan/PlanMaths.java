package com.wellnessz.e04.plan;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pure plan arithmetic: template expansion (interventions, follow-up dates) and expected-recovery variance. */
public final class PlanMaths {

    private PlanMaths() {
    }

    /** An intervention of a plan; dates are optional. */
    public record Intervention(String type, String description, String frequency, LocalDate startDate, LocalDate endDate,
                               String providerCode) {
    }

    public record Variance(int actualDays, int expectedDays, BigDecimal pctOver, boolean alert) {
    }

    /**
     * Expands template interventions ({@code {type, description, frequency, weeks}}) into dated interventions starting on
     * {@code start}: each runs for its number of weeks (at least one day).
     */
    public static List<Intervention> expandInterventions(List<Map<String, Object>> template, LocalDate start) {
        List<Intervention> out = new ArrayList<>();
        for (Map<String, Object> t : template) {
            int weeks = t.get("weeks") instanceof Number n ? Math.max(0, n.intValue()) : 0;
            LocalDate end = weeks == 0 ? start : start.plusWeeks(weeks).minusDays(1);
            out.add(new Intervention(String.valueOf(t.get("type")), String.valueOf(t.get("description")),
                    t.get("frequency") == null ? null : String.valueOf(t.get("frequency")), start, end, null));
        }
        return out;
    }

    /** Follow-up due dates {@code start + d} for each day offset of the template (CSV), ascending and distinct. */
    public static List<LocalDate> followUpDates(String csvDays, LocalDate start) {
        List<LocalDate> out = new ArrayList<>();
        if (csvDays == null || csvDays.isBlank()) {
            return out;
        }
        for (String s : csvDays.split(",")) {
            String v = s.trim();
            if (v.matches("\\d{1,3}")) {
                LocalDate d = start.plusDays(Integer.parseInt(v));
                if (!out.contains(d)) {
                    out.add(d);
                }
            }
        }
        out.sort(null);
        return out;
    }

    /**
     * Variance of the actual recovery duration (days from {@code from} to {@code to}) against the expected duration.
     * {@code pctOver = (actual − expected) / expected × 100} when the expectation is exceeded, else 0; an alert is due
     * when {@code pctOver} is strictly above the threshold. {@code null} when no expectation is known.
     */
    public static Variance variance(LocalDate from, LocalDate to, Integer expectedDays, int thresholdPct) {
        if (from == null || to == null || expectedDays == null || expectedDays <= 0) {
            return null;
        }
        int actual = (int) Math.max(0, ChronoUnit.DAYS.between(from, to));
        BigDecimal pct = actual <= expectedDays ? BigDecimal.ZERO.setScale(1)
                : BigDecimal.valueOf((long) actual - expectedDays).multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(expectedDays), 1, RoundingMode.HALF_UP);
        return new Variance(actual, expectedDays, pct, pct.compareTo(BigDecimal.valueOf(thresholdPct)) > 0);
    }

    /** Expected-recovery date: start + expected days. */
    public static LocalDate expectedRecoveryDate(LocalDate from, Integer expectedDays) {
        return from == null || expectedDays == null ? null : from.plusDays(expectedDays);
    }
}
