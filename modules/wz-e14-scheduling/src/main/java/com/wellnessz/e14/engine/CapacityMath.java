package com.wellnessz.e14.engine;

import java.util.Collection;
import java.util.List;

/**
 * Capacity planning maths (E14-F05). Capacity of a session = its minutes ÷ the mean duration of the appointment types it
 * offers; the run rate is the mean monthly volume of the previous three months; what-if adds clinician sessions per week
 * and campaign days (four stations for a working day).
 */
public final class CapacityMath {

    public static final int STANDARD_SESSION_MINUTES = 480;
    public static final int CAMPAIGN_STATIONS = 4;

    private CapacityMath() {
    }

    public static double meanDuration(Collection<Integer> durations) {
        return durations.isEmpty() ? 30.0 : durations.stream().mapToInt(Integer::intValue).average().orElse(30.0);
    }

    public static int sessionCapacity(int minutes, double meanDuration) {
        return meanDuration <= 0 ? 0 : (int) Math.floor(minutes / meanDuration);
    }

    /** Mean of the last months' volumes, rounded. */
    public static int runRate(List<Integer> previousMonths) {
        return previousMonths.isEmpty() ? 0 : (int) Math.round(previousMonths.stream().mapToInt(Integer::intValue).average().orElse(0));
    }

    /** Extra capacity of {@code sessionsPerWeek} extra clinician sessions over {@code days} calendar days. */
    public static int extraSessionCapacity(int sessionsPerWeek, int sessionMinutes, double meanDuration, int days) {
        if (sessionsPerWeek <= 0 || days <= 0) {
            return 0;
        }
        return (int) Math.floor(sessionsPerWeek * (days / 7.0) * sessionCapacity(sessionMinutes, meanDuration));
    }

    /** Capacity of one campaign day: four stations over a standard working day. */
    public static int campaignDayCapacity(double meanDuration) {
        return CAMPAIGN_STATIONS * sessionCapacity(STANDARD_SESSION_MINUTES, meanDuration);
    }

    /** Splits {@code total} over {@code parts} as evenly as possible (remainder to the first parts). */
    public static int share(int total, int parts, int index) {
        if (parts <= 0) {
            return 0;
        }
        return total / parts + (index < total % parts ? 1 : 0);
    }

    public static double utilisationPct(int booked, int capacity) {
        return capacity <= 0 ? 0.0 : Math.round(booked * 1000.0 / capacity) / 10.0;
    }

    public static double pct(long part, long whole) {
        return whole <= 0 ? 0.0 : Math.round(part * 1000.0 / whole) / 10.0;
    }
}
