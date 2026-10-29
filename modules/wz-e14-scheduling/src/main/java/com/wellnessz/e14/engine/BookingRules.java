package com.wellnessz.e14.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pure booking rules: employee self-booking (E14-F01), appointment reminder windows (T−48 h / T−2 h), no-show detection,
 * app check-in window and queue tickets (E14-F03).
 */
public final class BookingRules {

    private BookingRules() {
    }

    public enum Reminder { T48, T2 }

    /**
     * Self-booking rules: the type is self-bookable, the start is at least {@code minNoticeHours} ahead and at most
     * {@code maxDaysAhead} days ahead, and (when {@code onePerType}) the employee has no other open booking of the type.
     *
     * @return the violated rule code, empty when the booking is allowed
     */
    public static Optional<String> selfBookingViolation(boolean selfBookable, Instant startsAt, Instant now, int minNoticeHours,
                                                        int maxDaysAhead, boolean hasOpenBookingOfType, boolean onePerType) {
        if (!selfBookable) {
            return Optional.of("not_self_bookable");
        }
        return timingViolation(startsAt, now, minNoticeHours, maxDaysAhead)
                .or(() -> onePerType && hasOpenBookingOfType ? Optional.of("already_booked") : Optional.empty());
    }

    /** Notice / horizon rules shared by self-booking and employee rescheduling. */
    public static Optional<String> timingViolation(Instant startsAt, Instant now, int minNoticeHours, int maxDaysAhead) {
        if (startsAt.isBefore(now.plus(Duration.ofHours(minNoticeHours)))) {
            return Optional.of("insufficient_notice");
        }
        if (startsAt.isAfter(now.plus(Duration.ofDays(maxDaysAhead)))) {
            return Optional.of("too_far_ahead");
        }
        return Optional.empty();
    }

    /**
     * Reminders due now. The T−48 h reminder is sent between T−48 h and T−2 h, the T−2 h reminder between T−2 h and T; a
     * reminder whose window had already opened when the appointment was booked is skipped (the booking confirmation covers it).
     */
    public static List<Reminder> remindersDue(Instant startsAt, Instant createdAt, Instant now, boolean sent48, boolean sent2) {
        List<Reminder> out = new ArrayList<>();
        Instant w48 = startsAt.minus(Duration.ofHours(48));
        Instant w2 = startsAt.minus(Duration.ofHours(2));
        if (!sent48 && !now.isBefore(w48) && now.isBefore(w2) && createdAt.isBefore(w48)) {
            out.add(Reminder.T48);
        }
        if (!sent2 && !now.isBefore(w2) && now.isBefore(startsAt) && createdAt.isBefore(w2)) {
            out.add(Reminder.T2);
        }
        return out;
    }

    /** BOOKED / CHECKED_IN appointments not started {@code graceMinutes} after their end are no-shows. */
    public static boolean isNoShow(String status, Instant endsAt, Instant startedAt, Instant now, int graceMinutes) {
        return ("BOOKED".equals(status) || "CHECKED_IN".equals(status)) && startedAt == null
                && now.isAfter(endsAt.plus(Duration.ofMinutes(graceMinutes)));
    }

    /** App / kiosk check-in: from {@code windowMinutes} before the start until the appointment's end. */
    public static boolean withinCheckInWindow(Instant startsAt, Instant endsAt, Instant now, int windowMinutes) {
        return !now.isBefore(startsAt.minus(Duration.ofMinutes(windowMinutes))) && now.isBefore(endsAt);
    }

    /** Queue ticket for the n-th check-in of the day at a site ("A001" … "A999", then "B000" …). */
    public static String ticket(long n) {
        long letter = Math.max(0, n / 1000) % 26;
        return (char) ('A' + letter) + String.format(java.util.Locale.ROOT, "%03d", n % 1000);
    }

    /** Estimated wait: the appointments ahead in the queue × mean duration, shared by the clinicians on duty. */
    public static int estimatedWaitMinutes(int ahead, double meanDurationMinutes, int cliniciansOnDuty) {
        if (ahead <= 0) {
            return 0;
        }
        return (int) Math.round(ahead * meanDurationMinutes / Math.max(1, cliniciansOnDuty));
    }
}
