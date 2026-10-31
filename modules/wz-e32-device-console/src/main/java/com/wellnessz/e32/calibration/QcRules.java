package com.wellnessz.e32.calibration;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wellnessz.e32.support.Docs;

/**
 * Daily / periodic check and QC-lot evaluation with lockout (E32-HLR-04, E32-F04, contract §13.6). A check type is
 * {@code VALID} while the last check passed and is within its interval (+ grace); {@code DUE} when never done or overdue;
 * {@code FAILED} when the latest check failed (or the QC lot has expired). {@code DUE} and {@code FAILED} lock the device
 * out of capture when the schedule says {@code lockout}. Pure functions.
 */
public final class QcRules {

    private QcRules() {
    }

    public record Schedule(String family, String checkType, int intervalHours, int graceMinutes, boolean lockout, int reminderBeforeHours,
                           boolean perLot, String description) {
    }

    public record Check(String checkType, Instant performedAt, String outcome, String lot, LocalDate lotExpiry) {
    }

    /** @param verdict VALID | DUE | FAILED */
    public record Verdict(String checkType, String verdict, Instant lastCheckAt, Instant dueAt, String lot, LocalDate lotExpiry, boolean lockout) {

        public boolean blocks() {
            return lockout && ("DUE".equals(verdict) || "FAILED".equals(verdict));
        }
    }

    /**
     * @param checks checks of this type, any order
     * @param lot    when not null (per-lot schedules), only checks of that lot count
     */
    public static Verdict evaluate(Schedule s, List<Check> checks, Instant at, String lot, LocalDate day) {
        List<Check> relevant = new ArrayList<>();
        for (Check c : checks) {
            if (c.checkType().equals(s.checkType()) && (lot == null || !s.perLot() || lot.equals(c.lot())) && !c.performedAt().isAfter(at)) {
                relevant.add(c);
            }
        }
        relevant.sort((a, b) -> b.performedAt().compareTo(a.performedAt()));
        if (relevant.isEmpty()) {
            return new Verdict(s.checkType(), "DUE", null, null, lot, null, s.lockout());
        }
        Check latest = relevant.get(0);
        if ("FAIL".equals(latest.outcome())) {
            return new Verdict(s.checkType(), "FAILED", latest.performedAt(), latest.performedAt(), latest.lot(), latest.lotExpiry(), s.lockout());
        }
        Instant due = latest.performedAt().plus(Duration.ofHours(s.intervalHours())).plus(Duration.ofMinutes(s.graceMinutes()));
        if (s.perLot() && latest.lotExpiry() != null && day != null && latest.lotExpiry().isBefore(day)) {
            return new Verdict(s.checkType(), "FAILED", latest.performedAt(), due, latest.lot(), latest.lotExpiry(), s.lockout());
        }
        if (at.isAfter(due)) {
            return new Verdict(s.checkType(), "DUE", latest.performedAt(), due, latest.lot(), latest.lotExpiry(), s.lockout());
        }
        return new Verdict(s.checkType(), "VALID", latest.performedAt(), due, latest.lot(), latest.lotExpiry(), s.lockout());
    }

    /**
     * The verdict the engine sees for an instance ({@code DeviceCatalogueLookup.qcStatus}): the first blocking verdict
     * (FAILED before DUE), otherwise the valid verdict that falls due first; {@code NOT_REQUIRED} without schedules.
     */
    public static Verdict aggregate(List<Verdict> verdicts) {
        if (verdicts.isEmpty()) {
            return new Verdict(null, "NOT_REQUIRED", null, null, null, null, false);
        }
        Verdict failed = verdicts.stream().filter(v -> v.blocks() && "FAILED".equals(v.verdict())).findFirst().orElse(null);
        if (failed != null) {
            return failed;
        }
        Verdict due = verdicts.stream().filter(Verdict::blocks).findFirst().orElse(null);
        if (due != null) {
            return due;
        }
        return verdicts.stream().filter(v -> v.dueAt() != null).min((a, b) -> a.dueAt().compareTo(b.dueAt())).orElse(verdicts.get(0));
    }

    /**
     * Effective schedules of an instance: the family schedules, tightened (shorter interval, lockout kept) or extended by the
     * instance's {@code common.calibrationSchedule}.
     */
    public static List<Schedule> effective(List<Schedule> family, List<Object> instanceOverrides) {
        Map<String, Schedule> out = new LinkedHashMap<>();
        for (Schedule s : family) {
            out.put(s.checkType(), s);
        }
        for (Object o : instanceOverrides) {
            Map<String, Object> m = Docs.map(o);
            String type = Docs.str(m.get("checkType"));
            Integer interval = Docs.integer(m.get("intervalHours"));
            if (type == null || interval == null) {
                continue;
            }
            Schedule base = out.get(type);
            if (base == null) {
                out.put(type, new Schedule(null, type, interval, 0, Docs.bool(m.get("lockout"), true), Math.min(2, interval), false,
                        "Instance-specific check"));
            } else {
                out.put(type, new Schedule(base.family(), type, Math.min(base.intervalHours(), interval), base.graceMinutes(),
                        base.lockout() || Docs.bool(m.get("lockout"), true), base.reminderBeforeHours(), base.perLot(), base.description()));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** A schedule change that lengthens the interval or the grace, or removes lockout, needs four-eyes approval. */
    public static boolean relaxes(Schedule current, int intervalHours, int graceMinutes, boolean lockout) {
        return intervalHours > current.intervalHours() || graceMinutes > current.graceMinutes() || current.lockout() && !lockout;
    }
}
