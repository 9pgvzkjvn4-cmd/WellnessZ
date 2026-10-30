package com.wellnessz.e11.communicable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contact-tracing rules (E11-F02, pure): contacts are people whose duties overlap the index case's duties inside the tracing
 * window – on the same duty (flight / shift), in the same proximity group (aircraft / work area) or in the same shared
 * facility. Risk: {@code HIGH} = same proximity group for at least the high-risk minutes (default 15); {@code MEDIUM} =
 * at least the high-risk minutes on the same duty or in the same facility; {@code LOW} = any shorter overlap.
 */
public final class TracingRules {

    /** Contact types, strongest first. */
    public enum ContactType { PROXIMITY_GROUP, SAME_DUTY, FACILITY }

    public enum Risk { HIGH, MEDIUM, LOW }

    public static final String FACILITY_SOURCE = "FACILITY";

    private TracingRules() {
    }

    /** A duty assignment as seen by the rules. */
    public record Duty(String employeeId, String source, String dutyRef, String proximityGroup, Instant startsAt, Instant endsAt) {
    }

    /** A traced contact. */
    public record Found(String employeeId, ContactType contactType, String dutyRef, int overlapMinutes, Risk risk) {
    }

    /** Minutes during which both duties ran inside the tracing window (0 when they do not overlap). */
    public static long overlapMinutes(Instant aStart, Instant aEnd, Instant bStart, Instant bEnd, Instant windowFrom, Instant windowTo) {
        Instant start = max(max(aStart, bStart), windowFrom);
        Instant end = min(min(aEnd, bEnd), windowTo);
        return end.isAfter(start) ? Duration.between(start, end).toMinutes() : 0;
    }

    public static Risk risk(ContactType type, int overlapMinutes, int highRiskMinutes) {
        if (overlapMinutes >= highRiskMinutes) {
            return type == ContactType.PROXIMITY_GROUP ? Risk.HIGH : Risk.MEDIUM;
        }
        return Risk.LOW;
    }

    /** How a candidate duty relates to an index duty, given the requested contact types; {@code null} = no contact. */
    static ContactType relation(Duty index, Duty other, Set<ContactType> include) {
        boolean sameGroup = index.proximityGroup() != null && index.proximityGroup().equals(other.proximityGroup());
        boolean sameDuty = index.dutyRef() != null && index.dutyRef().equals(other.dutyRef());
        boolean facility = FACILITY_SOURCE.equals(index.source()) || FACILITY_SOURCE.equals(other.source());
        if (facility) {
            return (sameGroup || sameDuty) && include.contains(ContactType.FACILITY) ? ContactType.FACILITY : null;
        }
        if (sameGroup && include.contains(ContactType.PROXIMITY_GROUP)) {
            return ContactType.PROXIMITY_GROUP;
        }
        if (sameDuty && include.contains(ContactType.SAME_DUTY)) {
            return ContactType.SAME_DUTY;
        }
        return null;
    }

    /**
     * Traces the contacts of {@code indexEmployeeId}. Overlaps of the same type are summed per person (capped at the window
     * length); the type giving the highest risk is reported.
     */
    public static List<Found> trace(String indexEmployeeId, List<Duty> indexDuties, List<Duty> candidates, Set<ContactType> include,
                                    Instant windowFrom, Instant windowTo, int highRiskMinutes) {
        long windowMinutes = Math.max(0, Duration.between(windowFrom, windowTo).toMinutes());
        Map<String, Map<ContactType, long[]>> minutes = new LinkedHashMap<>();
        Map<String, Map<ContactType, String>> dutyRefs = new LinkedHashMap<>();
        java.util.Set<String> seenPairs = new java.util.HashSet<>();
        for (Duty other : candidates) {
            if (other.employeeId() == null || other.employeeId().equals(indexEmployeeId)) {
                continue;
            }
            for (Duty index : indexDuties) {
                ContactType type = relation(index, other, include);
                if (type == null) {
                    continue;
                }
                // the same candidate duty may be returned by several look-ups – count each duty pair once
                String pair = index.hashCode() + "|" + other.employeeId() + "|" + other.dutyRef() + "|" + other.startsAt() + "|" + other.source();
                if (!seenPairs.add(pair)) {
                    continue;
                }
                long ov = overlapMinutes(index.startsAt(), index.endsAt(), other.startsAt(), other.endsAt(), windowFrom, windowTo);
                if (ov <= 0) {
                    continue;
                }
                minutes.computeIfAbsent(other.employeeId(), k -> new EnumMap<>(ContactType.class)).computeIfAbsent(type, k -> new long[1])[0] += ov;
                dutyRefs.computeIfAbsent(other.employeeId(), k -> new EnumMap<>(ContactType.class)).putIfAbsent(type, other.dutyRef());
            }
        }
        List<Found> out = new ArrayList<>();
        minutes.forEach((employeeId, byType) -> {
            Found best = null;
            for (ContactType type : ContactType.values()) {
                long[] m = byType.get(type);
                if (m == null) {
                    continue;
                }
                int total = (int) Math.min(m[0], windowMinutes);
                Found f = new Found(employeeId, type, dutyRefs.get(employeeId).get(type), total, risk(type, total, highRiskMinutes));
                if (best == null || f.risk().ordinal() < best.risk().ordinal()) {
                    best = f;
                }
            }
            out.add(best);
        });
        out.sort(Comparator.comparing(Found::risk).thenComparing(Found::overlapMinutes, Comparator.reverseOrder())
                .thenComparing(Found::employeeId));
        return out;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
