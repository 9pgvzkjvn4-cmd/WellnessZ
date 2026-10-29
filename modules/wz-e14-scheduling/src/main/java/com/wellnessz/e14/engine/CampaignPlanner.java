package com.wellnessz.e14.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Campaign slot generation (E14-F02): days × stations × slots per day, in the site's time zone. */
public final class CampaignPlanner {

    private CampaignPlanner() {
    }

    public record SlotDef(LocalDate day, int station, Instant startsAt, Instant endsAt) {
    }

    public static int slotsPerDay(LocalTime dayStart, LocalTime dayEnd, int slotMinutes) {
        long minutes = Duration.between(dayStart, dayEnd).toMinutes();
        return minutes <= 0 || slotMinutes <= 0 ? 0 : (int) (minutes / slotMinutes);
    }

    public static List<SlotDef> slots(List<LocalDate> days, LocalTime dayStart, LocalTime dayEnd, int slotMinutes, int stations, ZoneId zone) {
        int perDay = slotsPerDay(dayStart, dayEnd, slotMinutes);
        List<SlotDef> out = new ArrayList<>();
        for (LocalDate day : new TreeSet<>(days)) {
            for (int i = 0; i < perDay; i++) {
                LocalTime t = dayStart.plusMinutes((long) i * slotMinutes);
                Instant start = ZonedDateTime.of(day, t, zone).toInstant();
                Instant end = start.plus(Duration.ofMinutes(slotMinutes));
                for (int station = 1; station <= stations; station++) {
                    out.add(new SlotDef(day, station, start, end));
                }
            }
        }
        return out;
    }
}
