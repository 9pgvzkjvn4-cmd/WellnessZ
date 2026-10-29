package com.wellnessz.e05.restriction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Restriction ↔ job-demand matching (E05-BL-005). A restriction on a numeric dimension (e.g. LIFT_KG = 10) conflicts
 * with a job when the job's demand exceeds the restriction value; a restriction on a boolean dimension (e.g. NIGHT)
 * conflicts when the job demands it. Results are ranked: compatible first, then same department, then fewest
 * conflicting demands (fewest duties that would have to be removed), then job code.
 */
public final class MatchingEngine {

    public static final Set<String> NUMERIC = Set.of("LIFT_KG", "PUSH_PULL_KG", "HOURS_PER_DAY", "SCREEN_HOURS");

    private MatchingEngine() {
    }

    /** A restriction resolved against the catalogue: its demand dimension, the limit (numeric only) and functional label. */
    public record Rule(String code, String dimension, BigDecimal limit, String label) {
    }

    public record Profile(String jobCode, String title, String unit, Map<String, Object> demands) {
    }

    public record Conflict(String dimension, Object demand, String restriction) {
    }

    public record Match(String jobCode, String title, String unit, boolean compatible, List<Conflict> conflicts, int score,
                        boolean sameDepartment) {
    }

    public static List<Match> rank(List<Rule> rules, List<Profile> profiles, String employeeUnit) {
        List<Match> out = new ArrayList<>();
        for (Profile p : profiles) {
            out.add(match(rules, p, employeeUnit));
        }
        out.sort(Comparator.comparing(Match::compatible).reversed()
                .thenComparing(Comparator.comparing(Match::sameDepartment).reversed())
                .thenComparingInt(m -> m.conflicts().size())
                .thenComparing(Comparator.comparingInt(Match::score).reversed())
                .thenComparing(Match::jobCode));
        return out;
    }

    public static Match match(List<Rule> rules, Profile p, String employeeUnit) {
        List<Conflict> conflicts = new ArrayList<>();
        for (Rule r : rules) {
            Object demand = p.demands() == null ? null : p.demands().get(r.dimension());
            if (conflicts(r, demand)) {
                conflicts.add(new Conflict(r.dimension(), demand, r.label()));
            }
        }
        boolean same = employeeUnit != null && Objects.equals(employeeUnit, p.unit());
        int score = rules.isEmpty() ? 100 : (int) Math.round(100.0 * (rules.size() - conflicts.size()) / rules.size());
        return new Match(p.jobCode(), p.title(), p.unit(), conflicts.isEmpty(), List.copyOf(conflicts), score, same);
    }

    static boolean conflicts(Rule r, Object demand) {
        if (demand == null) {
            return false;
        }
        if (NUMERIC.contains(r.dimension())) {
            if (!(demand instanceof Number n) || r.limit() == null) {
                return false;
            }
            return BigDecimal.valueOf(n.doubleValue()).compareTo(r.limit()) > 0;
        }
        return Boolean.TRUE.equals(demand);
    }
}
