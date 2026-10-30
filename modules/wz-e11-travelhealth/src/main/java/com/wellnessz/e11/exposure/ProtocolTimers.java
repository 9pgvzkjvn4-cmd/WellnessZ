package com.wellnessz.e11.exposure;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Protocol timers of exposure pathways (E11-F03, pure): due times are offsets from the time of exposure; serology
 * appointments are ISO-8601 periods from the exposure date. A pending timer is {@code OVERDUE} once its due time has passed;
 * the {@code exposure-timers} job alerts once per task and level – "due soon" inside the alert window, "overdue" after it.
 */
public final class ProtocolTimers {

    public static final String PENDING = "PENDING";
    public static final String DONE = "DONE";
    public static final String OVERDUE = "OVERDUE";

    public enum Alert { DUE_SOON, OVERDUE }

    private ProtocolTimers() {
    }

    public record TimerDef(String code, String label, int offsetMinutes) {
    }

    public record SerologyDef(String code, String label, String offset) {
    }

    public static Instant dueAt(Instant occurredAt, int offsetMinutes) {
        return occurredAt.plusSeconds(offsetMinutes * 60L);
    }

    public static LocalDate dueOn(Instant occurredAt, String isoPeriod) {
        try {
            return LocalDate.ofInstant(occurredAt, ZoneOffset.UTC).plus(Period.parse(isoPeriod));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid serology period " + isoPeriod, e);
        }
    }

    /** Timer status at {@code now}. */
    public static String status(Instant dueAt, Instant completedAt, Instant now) {
        if (completedAt != null) {
            return DONE;
        }
        return dueAt != null && now.isAfter(dueAt) ? OVERDUE : PENDING;
    }

    /** Serology status on {@code today}. */
    public static String status(LocalDate dueOn, boolean done, LocalDate today) {
        if (done) {
            return DONE;
        }
        return dueOn != null && today.isAfter(dueOn) ? OVERDUE : PENDING;
    }

    /**
     * The alert due for a pending timer, if any: {@code OVERDUE} once past due (sent once), {@code DUE_SOON} inside the alert
     * window before the deadline (sent once, skipped when the timer is already overdue).
     */
    public static Optional<Alert> alert(Instant dueAt, Instant now, int alertBeforeMinutes, boolean dueSoonSent, boolean overdueSent) {
        if (dueAt == null) {
            return Optional.empty();
        }
        if (now.isAfter(dueAt)) {
            return overdueSent ? Optional.empty() : Optional.of(Alert.OVERDUE);
        }
        if (!now.isBefore(dueAt.minusSeconds(alertBeforeMinutes * 60L))) {
            return dueSoonSent ? Optional.empty() : Optional.of(Alert.DUE_SOON);
        }
        return Optional.empty();
    }

    /** Whether a task was completed inside its protocol window (KPI, e.g. PEP decision within 2 hours). */
    public static boolean withinWindow(Instant dueAt, Instant completedAt) {
        return completedAt != null && dueAt != null && !completedAt.isAfter(dueAt);
    }
}
