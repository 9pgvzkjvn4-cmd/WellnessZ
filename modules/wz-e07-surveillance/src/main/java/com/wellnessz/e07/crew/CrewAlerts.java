package com.wellnessz.e07.crew;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Crew medical certificate status and expiry alert bands (E07-F04): VALID › EXPIRING (≤ 90 days) › EXPIRED (after the
 * valid-until date) › SUSPENDED; alerts at 90 / 60 / 30 days – one per band, the tightest band reached first (a certificate
 * imported 45 days before expiry gets the 60-day alert only, never a burst of 90 + 60).
 */
public final class CrewAlerts {

    public static final List<Integer> BANDS = List.of(90, 60, 30);
    public static final int EXPIRING_DAYS = 90;

    private CrewAlerts() {
    }

    public static String status(LocalDate validUntil, boolean suspended, LocalDate today) {
        if (suspended || validUntil == null) {
            return "SUSPENDED";
        }
        if (validUntil.isBefore(today)) {
            return "EXPIRED";
        }
        return ChronoUnit.DAYS.between(today, validUntil) <= EXPIRING_DAYS ? "EXPIRING" : "VALID";
    }

    /** @return the alert band to send now, if any */
    public static Optional<Integer> dueBand(LocalDate validUntil, LocalDate today, Collection<Integer> alreadySent) {
        if (validUntil == null || validUntil.isBefore(today)) {
            return Optional.empty();
        }
        long daysLeft = ChronoUnit.DAYS.between(today, validUntil);
        return BANDS.stream().filter(b -> daysLeft <= b && !alreadySent.contains(b)).min(Integer::compare)
                .filter(b -> alreadySent.stream().noneMatch(s -> s < b));
    }

    /** Bands recorded as sent after sending {@code band}: the band itself and every wider band. */
    public static Set<Integer> markSent(Collection<Integer> alreadySent, int band) {
        Set<Integer> out = new TreeSet<>(alreadySent);
        BANDS.stream().filter(b -> b >= band).forEach(out::add);
        return out;
    }
}
