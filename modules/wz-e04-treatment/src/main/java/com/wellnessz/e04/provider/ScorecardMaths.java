package com.wellnessz.e04.provider;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Provider scorecard arithmetic (E04-F06): timeliness (median working days to appointment against the SLA, reports within
 * 7 days), report quality (OH rating 1–5), outcome (cases with improved pain score) and cost (average invoice against the
 * contracted rate), each 0–100; overall = weighted mean of the available components (30 / 25 / 25 / 20).
 */
public final class ScorecardMaths {

    static final double W_TIMELINESS = 0.30;
    static final double W_QUALITY = 0.25;
    static final double W_OUTCOME = 0.25;
    static final double W_COST = 0.20;

    private ScorecardMaths() {
    }

    public record Input(List<Long> workingDaysToAppointment, int reportsDue, int reportsWithin7Days, List<Integer> ratings, int outcomesMeasured,
                        int outcomesImproved, List<BigDecimal> invoices, Integer slaAppointmentDays, BigDecimal contractRate) {
    }

    public record Result(BigDecimal medianDaysToAppointment, BigDecimal reportWithin7DaysPct, BigDecimal avgReportQuality,
                         BigDecimal outcomeImprovedPct, BigDecimal avgInvoice, BigDecimal timeliness, BigDecimal reportQuality,
                         BigDecimal outcome, BigDecimal cost, BigDecimal overall) {
    }

    public static Result compute(Input in) {
        BigDecimal median = median(in.workingDaysToAppointment());
        BigDecimal within7 = pct(in.reportsWithin7Days(), in.reportsDue());
        BigDecimal quality = in.ratings().isEmpty() ? null
                : BigDecimal.valueOf(in.ratings().stream().mapToInt(Integer::intValue).average().orElse(0)).setScale(1, RoundingMode.HALF_UP);
        BigDecimal improved = pct(in.outcomesImproved(), in.outcomesMeasured());
        BigDecimal avgInvoice = in.invoices().isEmpty() ? null
                : in.invoices().stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(in.invoices().size()), 2, RoundingMode.HALF_UP);

        Double apptScore = null;
        if (median != null) {
            int sla = in.slaAppointmentDays() == null ? 10 : in.slaAppointmentDays();
            double m = median.doubleValue();
            apptScore = m <= sla ? 100.0 : Math.max(0, 100 - (m - sla) * 10);
        }
        Double timeliness = mean(apptScore, within7 == null ? null : within7.doubleValue());
        Double qualityScore = quality == null ? null : quality.doubleValue() / 5 * 100;
        Double outcomeScore = improved == null ? null : improved.doubleValue();
        Double costScore = null;
        if (avgInvoice != null && in.contractRate() != null && in.contractRate().signum() > 0) {
            costScore = avgInvoice.compareTo(in.contractRate()) <= 0 ? 100.0 : in.contractRate().doubleValue() / avgInvoice.doubleValue() * 100;
        }
        double sum = 0;
        double weights = 0;
        double[][] parts = {{nz(timeliness), W_TIMELINESS, timeliness == null ? 0 : 1}, {nz(qualityScore), W_QUALITY, qualityScore == null ? 0 : 1},
                {nz(outcomeScore), W_OUTCOME, outcomeScore == null ? 0 : 1}, {nz(costScore), W_COST, costScore == null ? 0 : 1}};
        for (double[] p : parts) {
            if (p[2] > 0) {
                sum += p[0] * p[1];
                weights += p[1];
            }
        }
        BigDecimal overall = weights == 0 ? null : bd(sum / weights);
        return new Result(median, within7, quality, improved, avgInvoice, bd(timeliness), bd(qualityScore), bd(outcomeScore), bd(costScore), overall);
    }

    public static BigDecimal median(Collection<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> s = new ArrayList<>(values);
        s.sort(null);
        int n = s.size();
        double m;
        if (n % 2 == 1) {
            m = s.get(n / 2).doubleValue();
        } else {
            m = (s.get(n / 2 - 1).doubleValue() + s.get(n / 2).doubleValue()) / 2.0;
        }
        return BigDecimal.valueOf(m).setScale(1, RoundingMode.HALF_UP);
    }

    public static BigDecimal pct(int part, int total) {
        return total <= 0 ? null : BigDecimal.valueOf(part * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    /** Working days (Mon–Fri) from {@code from} to {@code to} (UTC dates); 0 when the same day or reversed. */
    public static long workingDays(Instant from, Instant to) {
        LocalDate a = LocalDate.ofInstant(from, ZoneOffset.UTC);
        LocalDate b = LocalDate.ofInstant(to, ZoneOffset.UTC);
        long n = 0;
        for (LocalDate d = a.plusDays(1); !d.isAfter(b); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                n++;
            }
        }
        return n;
    }

    private static Double mean(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : (a + b) / 2;
    }

    private static double nz(Double d) {
        return d == null ? 0 : d;
    }

    private static BigDecimal bd(Double d) {
        return d == null ? null : BigDecimal.valueOf(d).setScale(1, RoundingMode.HALF_UP);
    }
}
