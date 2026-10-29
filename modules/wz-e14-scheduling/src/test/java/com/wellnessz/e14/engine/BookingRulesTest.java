package com.wellnessz.e14.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class BookingRulesTest {

    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");

    @Test
    void selfBookingRules() {
        Instant in2Days = NOW.plus(Duration.ofDays(2));
        assertThat(BookingRules.selfBookingViolation(true, in2Days, NOW, 24, 60, false, true)).isEmpty();
        assertThat(BookingRules.selfBookingViolation(false, in2Days, NOW, 24, 60, false, true)).contains("not_self_bookable");
        assertThat(BookingRules.selfBookingViolation(true, NOW.plus(Duration.ofHours(23)), NOW, 24, 60, false, true)).contains("insufficient_notice");
        assertThat(BookingRules.selfBookingViolation(true, NOW.plus(Duration.ofHours(24)), NOW, 24, 60, false, true)).isEmpty();
        assertThat(BookingRules.selfBookingViolation(true, NOW.plus(Duration.ofDays(61)), NOW, 24, 60, false, true)).contains("too_far_ahead");
        assertThat(BookingRules.selfBookingViolation(true, NOW.plus(Duration.ofDays(60)), NOW, 24, 60, false, true)).isEmpty();
        assertThat(BookingRules.selfBookingViolation(true, in2Days, NOW, 24, 60, true, true)).contains("already_booked");
        assertThat(BookingRules.selfBookingViolation(true, in2Days, NOW, 24, 60, true, false)).isEmpty();
    }

    @Test
    void reminderWindows() {
        Instant start = NOW.plus(Duration.ofHours(47));
        Instant bookedLongAgo = NOW.minus(Duration.ofDays(10));
        assertThat(BookingRules.remindersDue(start, bookedLongAgo, NOW, false, false)).containsExactly(BookingRules.Reminder.T48);
        assertThat(BookingRules.remindersDue(start, bookedLongAgo, NOW, true, false)).isEmpty();
        // before the 48 h window: nothing
        assertThat(BookingRules.remindersDue(NOW.plus(Duration.ofHours(49)), bookedLongAgo, NOW, false, false)).isEmpty();
        // inside the 2 h window: the T-2 h reminder (the 48 h one is no longer due)
        assertThat(BookingRules.remindersDue(NOW.plus(Duration.ofMinutes(90)), bookedLongAgo, NOW, false, false))
                .containsExactly(BookingRules.Reminder.T2);
        // booked 40 h ahead: no T-48 h reminder (already inside the window when booked – the confirmation covers it)
        assertThat(BookingRules.remindersDue(NOW.plus(Duration.ofHours(30)), NOW.minus(Duration.ofHours(10)), NOW, false, false)).isEmpty();
        // started: nothing
        assertThat(BookingRules.remindersDue(NOW.minusSeconds(60), bookedLongAgo, NOW, false, false)).isEmpty();
    }

    @Test
    void noShowDetection() {
        Instant end = NOW.minus(Duration.ofMinutes(31));
        assertThat(BookingRules.isNoShow("BOOKED", end, null, NOW, 30)).isTrue();
        assertThat(BookingRules.isNoShow("CHECKED_IN", end, null, NOW, 30)).isTrue();
        assertThat(BookingRules.isNoShow("BOOKED", NOW.minus(Duration.ofMinutes(29)), null, NOW, 30)).isFalse();
        assertThat(BookingRules.isNoShow("IN_PROGRESS", end, NOW.minusSeconds(3600), NOW, 30)).isFalse();
        assertThat(BookingRules.isNoShow("CHECKED_IN", end, NOW.minusSeconds(3600), NOW, 30)).isFalse();
        assertThat(BookingRules.isNoShow("ATTENDED", end, null, NOW, 30)).isFalse();
        assertThat(BookingRules.isNoShow("CANCELLED", end, null, NOW, 30)).isFalse();
    }

    @Test
    void checkInWindowTicketsAndWaitEstimate() {
        Instant start = NOW.plus(Duration.ofMinutes(45));
        assertThat(BookingRules.withinCheckInWindow(start, start.plusSeconds(600), NOW, 60)).isTrue();
        assertThat(BookingRules.withinCheckInWindow(NOW.plus(Duration.ofMinutes(61)), NOW.plus(Duration.ofMinutes(71)), NOW, 60)).isFalse();
        assertThat(BookingRules.withinCheckInWindow(NOW.minus(Duration.ofMinutes(20)), NOW.minus(Duration.ofMinutes(10)), NOW, 60)).isFalse();
        assertThat(BookingRules.ticket(1)).isEqualTo("A001");
        assertThat(BookingRules.ticket(12)).isEqualTo("A012");
        assertThat(BookingRules.ticket(1001)).isEqualTo("B001");
        assertThat(BookingRules.estimatedWaitMinutes(0, 20, 2)).isZero();
        assertThat(BookingRules.estimatedWaitMinutes(3, 20, 2)).isEqualTo(30);
        assertThat(BookingRules.estimatedWaitMinutes(3, 20, 0)).isEqualTo(60);
    }
}
