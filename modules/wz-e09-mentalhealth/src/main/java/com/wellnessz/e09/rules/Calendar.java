package com.wellnessz.e09.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Working days (Monday–Friday) and reporting quarters ({@code 2026-Q3}). */
public final class Calendar {

    private static final Pattern QUARTER = Pattern.compile("(\\d{4})-Q([1-4])");

    public record Quarter(String code, LocalDate from, LocalDate to) {
    }

    private Calendar() {
    }

    public static LocalDate plusWorkingDays(LocalDate start, int days) {
        LocalDate d = start;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }

    /** @throws IllegalArgumentException for anything but {@code YYYY-Qn} */
    public static Quarter quarter(String code) {
        Matcher m = code == null ? null : QUARTER.matcher(code);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("quarter must be YYYY-Qn");
        }
        int year = Integer.parseInt(m.group(1));
        int q = Integer.parseInt(m.group(2));
        LocalDate from = LocalDate.of(year, (q - 1) * 3 + 1, 1);
        return new Quarter(code, from, from.plusMonths(3).minusDays(1));
    }

    public static String quarterOf(LocalDate d) {
        return d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }
}
