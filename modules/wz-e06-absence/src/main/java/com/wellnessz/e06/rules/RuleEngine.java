package com.wellnessz.e06.rules;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e06.model.AbsenceEnums;

/**
 * Pure absence trigger-rule engine (E06-F02). Evaluates the effective rules of an employee's subsidiary against their
 * absence history on a given day. Only sickness / injury episodes count; a rule looks at "recent" episodes (open, or
 * ended within {@link #RECENT_DAYS} days) so that a historical back-fill does not raise stale triggers.
 *
 * <p>Parameters per type: DURATION {@code {minDays}} (days &ge; minDays), FREQUENCY {@code {episodes, windowDays}},
 * PATTERN {@code {pattern:"MONDAY_FRIDAY", minOccurrences, windowDays}}, POST_INCIDENT {@code {withinDays}},
 * SAFETY_CRITICAL {@code {minDays}} (safety-critical job), CREW_SICKNESS {@code {minDays, episodes, windowDays}} (crew).
 * A subsidiary rule overrides the default rules of the same type and action for that subsidiary.
 */
public final class RuleEngine {

    /** Episodes open, or ended within this many days, are "recent". */
    public static final int RECENT_DAYS = 30;
    static final int MAX_PARAM = 3650;

    private RuleEngine() {
    }

    public record EpisodeFact(UUID id, String source, LocalDate start, LocalDate end, String absenceType) {

        /** Calendar days absent up to {@code asOf} (inclusive of the start day); 0 for future episodes. */
        public long days(LocalDate asOf) {
            if (start.isAfter(asOf)) {
                return 0;
            }
            LocalDate last = end == null || end.isAfter(asOf) ? asOf : end;
            return ChronoUnit.DAYS.between(start, last) + 1;
        }

        public boolean triggerable() {
            return absenceType != null && AbsenceEnums.TRIGGERABLE_TYPES.contains(absenceType);
        }

        boolean recent(LocalDate asOf) {
            return !start.isAfter(asOf) && (end == null || !end.isBefore(asOf.minusDays(RECENT_DAYS)));
        }

        boolean startsWithin(LocalDate asOf, int windowDays) {
            return !start.isAfter(asOf) && start.isAfter(asOf.minusDays(windowDays));
        }
    }

    /** Everything the engine needs to know about an employee – identifiers, flags and dates only. */
    public record Facts(String employeeId, String subsidiaryCode, boolean safetyCritical, boolean crew, List<EpisodeFact> episodes,
                        List<LocalDate> incidentDates) {
    }

    public record Rule(UUID id, String code, String name, String subsidiaryCode, String ruleType, Map<String, Object> params,
                       String action) {
    }

    public record Match(Rule rule, UUID episodeId, String detail) {
    }

    // ------------------------------------------------------------------ rule selection

    /** Default rules plus the subsidiary's rules; a subsidiary rule replaces the defaults with the same type and action. */
    public static List<Rule> effective(Collection<Rule> rules, String subsidiaryCode) {
        List<Rule> own = rules.stream().filter(r -> r.subsidiaryCode() != null && r.subsidiaryCode().equals(subsidiaryCode)).toList();
        List<Rule> out = new ArrayList<>(own);
        for (Rule r : rules) {
            if (r.subsidiaryCode() == null
                    && own.stream().noneMatch(o -> o.ruleType().equals(r.ruleType()) && o.action().equals(r.action()))) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparing((Rule r) -> r.subsidiaryCode() == null ? 1 : 0).thenComparing(Rule::code));
        return out;
    }

    /** Evaluates the effective rules; at most one match per action (first matching rule in effective order). */
    public static List<Match> evaluateAll(Collection<Rule> rules, Facts facts, LocalDate asOf) {
        Map<String, Match> byAction = new LinkedHashMap<>();
        for (Rule r : effective(rules, facts.subsidiaryCode())) {
            if (byAction.containsKey(r.action())) {
                continue;
            }
            evaluate(r, facts, asOf).ifPresent(m -> byAction.put(r.action(), m));
        }
        return List.copyOf(byAction.values());
    }

    public static Optional<Match> evaluate(Rule rule, Facts facts, LocalDate asOf) {
        List<EpisodeFact> eps = facts.episodes().stream().filter(EpisodeFact::triggerable).toList();
        Map<String, Object> p = rule.params();
        return switch (AbsenceEnums.RuleType.valueOf(rule.ruleType())) {
            case DURATION -> duration(rule, eps, asOf, param(p, "minDays"));
            case SAFETY_CRITICAL -> facts.safetyCritical() ? duration(rule, eps, asOf, param(p, "minDays")) : Optional.empty();
            case FREQUENCY -> frequency(rule, eps, asOf, param(p, "episodes"), param(p, "windowDays"));
            case PATTERN -> pattern(rule, eps, asOf, param(p, "minOccurrences"), param(p, "windowDays"));
            case POST_INCIDENT -> postIncident(rule, eps, facts.incidentDates(), asOf, param(p, "withinDays"));
            case CREW_SICKNESS -> crew(rule, facts, eps, asOf, p);
        };
    }

    private static Optional<Match> duration(Rule rule, List<EpisodeFact> eps, LocalDate asOf, int minDays) {
        return eps.stream().filter(e -> e.recent(asOf) && e.days(asOf) >= minDays)
                .max(Comparator.comparingLong(e -> e.days(asOf)))
                .map(e -> new Match(rule, e.id(), "Absence of " + e.days(asOf) + " days (threshold " + minDays + ")"));
    }

    private static Optional<Match> frequency(Rule rule, List<EpisodeFact> eps, LocalDate asOf, int episodes, int windowDays) {
        List<EpisodeFact> inWindow = eps.stream().filter(e -> e.startsWithin(asOf, windowDays))
                .sorted(Comparator.comparing(EpisodeFact::start)).toList();
        if (inWindow.size() < episodes || inWindow.stream().noneMatch(e -> e.recent(asOf))) {
            return Optional.empty();
        }
        EpisodeFact latest = inWindow.get(inWindow.size() - 1);
        return Optional.of(new Match(rule, latest.id(), inWindow.size() + " absences within " + windowDays + " days (threshold " + episodes + ")"));
    }

    private static Optional<Match> pattern(Rule rule, List<EpisodeFact> eps, LocalDate asOf, int minOccurrences, int windowDays) {
        Object pattern = rule.params().get("pattern");
        if (pattern != null && !"MONDAY_FRIDAY".equals(pattern)) {
            return Optional.empty();
        }
        List<EpisodeFact> adjacent = eps.stream().filter(e -> e.startsWithin(asOf, windowDays) && weekendAdjacent(e))
                .sorted(Comparator.comparing(EpisodeFact::start)).toList();
        if (adjacent.size() < minOccurrences || adjacent.stream().noneMatch(e -> e.recent(asOf))) {
            return Optional.empty();
        }
        return Optional.of(new Match(rule, adjacent.get(adjacent.size() - 1).id(),
                adjacent.size() + " Monday / Friday absences within " + windowDays + " days (threshold " + minOccurrences + ")"));
    }

    private static Optional<Match> postIncident(Rule rule, List<EpisodeFact> eps, List<LocalDate> incidents, LocalDate asOf, int withinDays) {
        if (incidents == null || incidents.isEmpty()) {
            return Optional.empty();
        }
        for (EpisodeFact e : eps) {
            if (!e.recent(asOf)) {
                continue;
            }
            for (LocalDate d : incidents) {
                if (!e.start().isBefore(d) && !e.start().isAfter(d.plusDays(withinDays))) {
                    return Optional.of(new Match(rule, e.id(), "Absence started " + ChronoUnit.DAYS.between(d, e.start())
                            + " days after a workplace incident (window " + withinDays + " days)"));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Match> crew(Rule rule, Facts facts, List<EpisodeFact> eps, LocalDate asOf, Map<String, Object> p) {
        boolean crew = facts.crew() || eps.stream().anyMatch(e -> AbsenceEnums.AbsenceSource.CREW.name().equals(e.source()));
        if (!crew) {
            return Optional.empty();
        }
        Optional<Match> longSpell = duration(rule, eps, asOf, param(p, "minDays"));
        if (longSpell.isPresent()) {
            return longSpell.map(m -> new Match(rule, m.episodeId(), "Crew sickness: " + m.detail()));
        }
        return frequency(rule, eps, asOf, param(p, "episodes"), param(p, "windowDays"))
                .map(m -> new Match(rule, m.episodeId(), "Crew sickness: " + m.detail()));
    }

    /** A Monday / Friday-adjacent absence: starts on a Monday or Friday, or ends on a Friday or Monday (touches a weekend). */
    public static boolean weekendAdjacent(EpisodeFact e) {
        DayOfWeek s = e.start().getDayOfWeek();
        DayOfWeek f = e.end() == null ? null : e.end().getDayOfWeek();
        return s == DayOfWeek.MONDAY || s == DayOfWeek.FRIDAY || f == DayOfWeek.FRIDAY || f == DayOfWeek.MONDAY;
    }

    // ------------------------------------------------------------------ parameters

    /** Required parameter names per rule type. */
    public static Set<String> requiredParams(String ruleType) {
        return switch (AbsenceEnums.RuleType.valueOf(ruleType)) {
            case DURATION, SAFETY_CRITICAL -> Set.of("minDays");
            case FREQUENCY -> Set.of("episodes", "windowDays");
            case PATTERN -> Set.of("minOccurrences", "windowDays");
            case POST_INCIDENT -> Set.of("withinDays");
            case CREW_SICKNESS -> Set.of("minDays", "episodes", "windowDays");
        };
    }

    /** Validates a rule's parameters: required integers between 1 and 3650, PATTERN only MONDAY_FRIDAY, no unknown keys. */
    public static Map<String, Object> validateParams(String ruleType, Map<String, Object> params) {
        if (params == null) {
            throw ApiException.badRequest("invalid_params", "params are required");
        }
        Set<String> required = requiredParams(ruleType);
        Map<String, Object> clean = new LinkedHashMap<>();
        for (String key : params.keySet()) {
            if (!required.contains(key) && !("PATTERN".equals(ruleType) && "pattern".equals(key))) {
                throw ApiException.badRequest("invalid_params", "Unknown parameter for " + ruleType + ": " + key);
            }
        }
        if ("PATTERN".equals(ruleType)) {
            Object pattern = params.getOrDefault("pattern", "MONDAY_FRIDAY");
            if (!"MONDAY_FRIDAY".equals(pattern)) {
                throw ApiException.badRequest("invalid_params", "Supported pattern: MONDAY_FRIDAY");
            }
            clean.put("pattern", "MONDAY_FRIDAY");
        }
        for (String key : required.stream().sorted().toList()) {
            Object v = params.get(key);
            if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.intValue() < 1 || n.intValue() > MAX_PARAM) {
                throw ApiException.badRequest("invalid_params", "Parameter " + key + " must be a whole number between 1 and " + MAX_PARAM);
            }
            clean.put(key, n.intValue());
        }
        return clean;
    }

    static int param(Map<String, Object> params, String key) {
        Object v = params == null ? null : params.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s && s.matches("\\d{1,4}")) {
            return Integer.parseInt(s);
        }
        throw new IllegalStateException("Rule parameter missing: " + key);
    }
}
