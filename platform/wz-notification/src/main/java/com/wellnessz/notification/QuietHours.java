package com.wellnessz.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Quiet hours in the recipient's local time zone (crew are frequently in other time zones). NORMAL messages
 * falling inside the window are deferred to its end; URGENT messages are sent immediately.
 */
@Component
public class QuietHours {

    private final LocalTime start;
    private final LocalTime end;

    public QuietHours(@Value("${wellnessz.notification.quiet-hours.start:21:00}") LocalTime start,
                      @Value("${wellnessz.notification.quiet-hours.end:07:00}") LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public Instant nextAllowed(Instant now, ZoneId zone, Priority priority) {
        if (priority == Priority.URGENT) {
            return now;
        }
        ZonedDateTime local = now.atZone(zone);
        LocalTime t = local.toLocalTime();
        boolean overnight = start.isAfter(end);
        boolean inside = overnight ? (!t.isBefore(start) || t.isBefore(end)) : (!t.isBefore(start) && t.isBefore(end));
        if (!inside) {
            return now;
        }
        ZonedDateTime release = local.with(end);
        if (!release.isAfter(local)) {
            release = release.plusDays(1);
        }
        return release.toInstant();
    }
}
