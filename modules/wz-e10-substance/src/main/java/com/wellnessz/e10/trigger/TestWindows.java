package com.wellnessz.e10.trigger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Policy time windows for post-incident and for-cause tests (E10-F02): alcohol within {@code alcoholWindowHours} (default 8 h)
 * and drugs within {@code drugWindowHours} (default 32 h) of the event; the countdown shown to collectors is the nearest open
 * window.
 */
public final class TestWindows {

    private TestWindows() {
    }

    /** Due times of an event-driven test. */
    public record Windows(Instant alcoholDueAt, Instant drugDueAt) {

        /** The window that closes first while it is still open (alcohol before collection, otherwise drugs). */
        public Instant nextDue(boolean alcoholRequired, boolean alcoholCollected, boolean drugsRequired) {
            if (alcoholRequired && !alcoholCollected && alcoholDueAt != null) {
                return alcoholDueAt;
            }
            return drugsRequired ? drugDueAt : alcoholDueAt;
        }
    }

    public static Windows windows(Instant eventAt, int alcoholWindowHours, int drugWindowHours) {
        if (eventAt == null) {
            throw new IllegalArgumentException("event time required");
        }
        return new Windows(eventAt.plus(Duration.ofHours(alcoholWindowHours)), eventAt.plus(Duration.ofHours(drugWindowHours)));
    }

    /** Hours left until {@code due} with one decimal (negative when overdue); null when there is no deadline. */
    public static BigDecimal hoursLeft(Instant now, Instant due) {
        if (due == null) {
            return null;
        }
        long seconds = Duration.between(now, due).getSeconds();
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 1, RoundingMode.FLOOR);
    }

    /** A collection is in window when it happened no later than the due time. */
    public static boolean inWindow(Instant collectedAt, Instant due) {
        return due == null || collectedAt == null || !collectedAt.isAfter(due);
    }
}
