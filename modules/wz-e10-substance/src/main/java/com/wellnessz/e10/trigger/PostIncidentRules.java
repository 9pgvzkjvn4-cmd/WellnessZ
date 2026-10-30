package com.wellnessz.e10.trigger;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The post-incident rule table ({@code substance.post_incident_rule}): the first active rule (lowest priority number) whose
 * incident class (null = any) and minimum severity (null = any) match decides whether a post-incident test is required.
 */
public final class PostIncidentRules {

    /** One row of the rule table. */
    public record Rule(long id, String name, int priority, boolean active, String incidentClass, String minSeverity, String action) {

        public boolean requiresTest() {
            return "TEST".equals(action);
        }
    }

    private PostIncidentRules() {
    }

    /** Severity rank: LOW 1, MEDIUM 2, HIGH / MAJOR 3, CRITICAL / SEVERE / CATASTROPHIC 4; unknown 0. */
    public static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "LOW", "MINOR" -> 1;
            case "MEDIUM", "MODERATE" -> 2;
            case "HIGH", "MAJOR", "SERIOUS" -> 3;
            case "CRITICAL", "SEVERE", "CATASTROPHIC" -> 4;
            default -> 0;
        };
    }

    public static Optional<Rule> match(List<Rule> rules, String incidentClass, String severity) {
        int rank = severityRank(severity);
        return rules.stream()
                .filter(Rule::active)
                .sorted(Comparator.comparingInt(Rule::priority).thenComparingLong(Rule::id))
                .filter(r -> r.incidentClass() == null || r.incidentClass().equalsIgnoreCase(incidentClass == null ? "" : incidentClass))
                .filter(r -> r.minSeverity() == null || rank >= severityRank(r.minSeverity()))
                .findFirst();
    }

    /** True when the matching rule (if any) requires a test. */
    public static boolean qualifies(List<Rule> rules, String incidentClass, String severity) {
        return match(rules, incidentClass, severity).map(Rule::requiresTest).orElse(false);
    }
}
