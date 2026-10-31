package com.wellnessz.e34.anomaly;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Clinic hours per site ({@code devicesec.site_hours}, {@code '*'} = default) for the off-hours anomaly rules. Cached for one
 * minute; with no row at all every time counts as within hours (no false alarms from missing configuration).
 */
@Component
public class SiteHours {

    /** Opening window of a site in its own time zone. */
    public record Window(ZoneId zone, LocalTime opens, LocalTime closes, Set<DayOfWeek> days) {

        public boolean contains(Instant at) {
            ZonedDateTime local = at.atZone(zone);
            if (!days.contains(local.getDayOfWeek())) {
                return false;
            }
            LocalTime t = local.toLocalTime();
            if (opens.equals(closes)) {
                return true;
            }
            return opens.isBefore(closes) ? !t.isBefore(opens) && t.isBefore(closes) : !t.isBefore(opens) || t.isBefore(closes);
        }

        public static Window parse(String zone, LocalTime opens, LocalTime closes, String days) {
            EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
            for (String d : days.split(",")) {
                String code = d.strip().toUpperCase(Locale.ROOT);
                for (DayOfWeek w : DayOfWeek.values()) {
                    if (w.name().startsWith(code) && code.length() >= 3) {
                        set.add(w);
                    }
                }
            }
            return new Window(ZoneId.of(zone), opens, closes, set);
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<String, Window> cache = Map.of();
    private volatile long loadedAt;

    public SiteHours(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public boolean outsideHours(String siteCode, Instant at) {
        Map<String, Window> windows = windows();
        Window w = siteCode == null ? null : windows.get(siteCode);
        if (w == null) {
            w = windows.get("*");
        }
        return w != null && !w.contains(at);
    }

    private Map<String, Window> windows() {
        long now = clock.millis();
        if (now - loadedAt > 60_000) {
            Map<String, Window> m = new HashMap<>();
            jdbc.query("select site_code, time_zone, opens, closes, days from devicesec.site_hours", rs -> {
                try {
                    m.put(rs.getString("site_code"), Window.parse(rs.getString("time_zone"), rs.getTime("opens").toLocalTime(),
                            rs.getTime("closes").toLocalTime(), rs.getString("days")));
                } catch (RuntimeException e) {
                    // ignore a malformed row – no off-hours alerting for that site
                }
            });
            cache = Map.copyOf(m);
            loadedAt = now;
        }
        return cache;
    }
}
