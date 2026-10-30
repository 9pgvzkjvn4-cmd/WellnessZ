package com.wellnessz.e09.rules;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Critical-incident stress management rules (E09-F04): trigger, affected-person list from duty rosters, 72-hour offer SLA and
 * the 1- / 4-week follow-up schedule.
 */
public final class CismRules {

    /** A duty assignment as far as CISM needs it. */
    public record Duty(String employeeId, String dutyRef, String proximityGroup, String dutyRole, Instant startsAt, Instant endsAt) {
    }

    public record Window(Instant from, Instant to) {
    }

    public record FollowUps(LocalDate oneWeek, LocalDate fourWeeks) {
    }

    private CismRules() {
    }

    /** Severity HIGH / CRITICAL or an incident class from the configured trigger list. */
    public static boolean triggers(String severity, String incidentClass, Collection<String> triggerClasses) {
        String sev = severity == null ? "" : severity.trim().toUpperCase(Locale.ROOT);
        if ("HIGH".equals(sev) || "CRITICAL".equals(sev)) {
            return true;
        }
        return incidentClass != null && triggerClasses.contains(incidentClass.trim().toUpperCase(Locale.ROOT));
    }

    public static Window window(Instant occurredAt, int hours) {
        Duration d = Duration.ofHours(Math.max(0, hours));
        return new Window(occurredAt.minus(d), occurredAt.plus(d));
    }

    /** Duty references and proximity groups the incident employee worked in during the window. */
    public static Set<String> dutyRefs(List<Duty> ownDuties) {
        return ownDuties.stream().map(Duty::dutyRef).filter(Objects::nonNull).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public static Set<String> proximityGroups(List<Duty> ownDuties) {
        return ownDuties.stream().map(Duty::proximityGroup).filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /**
     * Affected people: the incident employee (when known) first, then everyone on the same duty or proximity group in the window,
     * each once (employee id → duty role of their first matching duty).
     */
    public static Map<String, String> affected(String incidentEmployeeId, List<Duty> ownDuties, List<Duty> coDuties, Window window) {
        Map<String, String> out = new LinkedHashMap<>();
        if (incidentEmployeeId != null && !incidentEmployeeId.isBlank()) {
            String role = ownDuties.stream().map(Duty::dutyRole).filter(Objects::nonNull).findFirst().orElse(null);
            out.put(incidentEmployeeId, role);
        }
        for (Duty d : coDuties) {
            if (d.employeeId() == null || !overlaps(d, window)) {
                continue;
            }
            out.putIfAbsent(d.employeeId(), d.dutyRole());
        }
        return out;
    }

    static boolean overlaps(Duty d, Window w) {
        return d.startsAt() != null && d.endsAt() != null && d.startsAt().isBefore(w.to()) && d.endsAt().isAfter(w.from());
    }

    public static Instant offerDueBy(Instant occurredAt, int slaHours) {
        return occurredAt.plus(Duration.ofHours(slaHours));
    }

    /**
     * SLA status: {@code false} as soon as one offer was late or the deadline passed with someone not offered; {@code true} when
     * everyone was offered in time; {@code null} while offers are still pending inside the window.
     */
    public static Boolean slaMet(Instant dueBy, List<Instant> offerSentAt, Instant now) {
        boolean pending = false;
        for (Instant sent : offerSentAt) {
            if (sent == null) {
                pending = true;
            } else if (sent.isAfter(dueBy)) {
                return false;
            }
        }
        if (!pending) {
            return true;
        }
        return now.isAfter(dueBy) ? Boolean.FALSE : null;
    }

    /** Follow-ups 1 and 4 weeks after the event. */
    public static FollowUps followUps(LocalDate eventDate) {
        return new FollowUps(eventDate.plusWeeks(1), eventDate.plusWeeks(4));
    }
}
