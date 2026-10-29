package com.wellnessz.e07.immunisation;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Immunisation status of one requirement (E07-F05): COMPLETE, DUE (course in progress, booster within 30 days, or never
 * started), OVERDUE (next dose / booster date passed) or TITRE_NEEDED (course complete, protective titre required but missing).
 */
public final class ImmunisationRules {

    public static final int DUE_WINDOW_DAYS = 30;

    private ImmunisationRules() {
    }

    /** Requirement facts: doses in the primary course, cumulative schedule in months from dose 1, booster interval. */
    public record Requirement(String vaccineCode, int doses, List<Integer> scheduleMonths, Integer boosterMonths, boolean titreRequired) {
    }

    public record Dose(int doseNumber, LocalDate givenOn) {
    }

    public record Titre(boolean protective, LocalDate testedOn) {
    }

    public record Status(String status, int dosesGiven, LocalDate lastDoseOn, LocalDate nextDueOn) {
    }

    public static List<Integer> schedule(String csv) {
        List<Integer> out = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String t = s.trim();
                if (t.matches("\\d{1,3}")) {
                    out.add(Integer.parseInt(t));
                }
            }
        }
        return out;
    }

    public static Status evaluate(Requirement r, Collection<Dose> doses, Titre latestTitre, LocalDate today) {
        Map<Integer, LocalDate> byNumber = new TreeMap<>();
        for (Dose d : doses) {
            byNumber.merge(d.doseNumber(), d.givenOn(), (a, b) -> a.isBefore(b) ? a : b);
        }
        int given = byNumber.size();
        if (given == 0) {
            return new Status("DUE", 0, null, null);
        }
        LocalDate first = byNumber.values().stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate last = byNumber.values().stream().max(LocalDate::compareTo).orElseThrow();
        if (given < r.doses()) {
            List<Integer> s = r.scheduleMonths();
            LocalDate next;
            if (given < s.size()) {
                next = first.plusMonths(s.get(given));
            } else {
                next = last.plusMonths(1);
            }
            if (next.isBefore(last)) {
                next = last;
            }
            return new Status(next.isBefore(today) ? "OVERDUE" : "DUE", given, last, next);
        }
        if (r.titreRequired() && (latestTitre == null || !latestTitre.protective() || latestTitre.testedOn().isBefore(last))) {
            return new Status("TITRE_NEEDED", given, last, last.plusMonths(1));
        }
        if (r.boosterMonths() != null) {
            LocalDate next = last.plusMonths(r.boosterMonths());
            String status = next.isBefore(today) ? "OVERDUE" : next.isAfter(today.plusDays(DUE_WINDOW_DAYS)) ? "COMPLETE" : "DUE";
            return new Status(status, given, last, next);
        }
        return new Status("COMPLETE", given, last, null);
    }

    /** Titre antigen codes accepted for a vaccine (the vaccine code itself or the usual antibody name). */
    public static boolean titreFor(String vaccineCode, String antigen) {
        if (vaccineCode.equals(antigen)) {
            return true;
        }
        return switch (vaccineCode) {
            case "HEP_B" -> Set.of("ANTI_HBS", "HBSAB").contains(antigen);
            case "HEP_A" -> Set.of("ANTI_HAV").contains(antigen);
            case "MMR" -> Set.of("MEASLES_IGG", "RUBELLA_IGG").contains(antigen);
            default -> false;
        };
    }
}
