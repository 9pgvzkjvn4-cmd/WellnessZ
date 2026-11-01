package com.wellnessz.e31.association;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ASSOCIATE stage decision (E31-HLR-05, contract section 9). Priority WORKLIST → SESSION → BADGE → VENDOR_ID_MATCH: the
 * first method with a candidate at or above the auto-file threshold (0.95) wins, unless another employee's candidate is
 * within 0.05 of it (ambiguous) or the message conflicts with the session (association_conflict). Nothing below the
 * threshold is ever auto-filed: it lands in the unmatched queue with the candidates at or above the suggest threshold (0.60)
 * as suggestions. Restricted categories accept only WORKLIST or SESSION (and a clinician's manual resolution).
 */
public final class AssociationEngine {

    public static final List<String> PRIORITY = List.of("MANUAL_RESOLUTION", "ENTERED_IN_FORM", "AREA_MEASUREMENT", "WORKLIST", "SESSION", "BADGE",
            "VENDOR_ID_MATCH");
    static final Set<String> RESTRICTED_METHODS = Set.of("MANUAL_RESOLUTION", "ENTERED_IN_FORM", "WORKLIST", "SESSION");
    static final BigDecimal AMBIGUITY = new BigDecimal("0.05");

    private AssociationEngine() {
    }

    /** @param employeeId {@code null} only for {@code AREA_MEASUREMENT} (exposure feeds without a person) */
    public record Candidate(String employeeId, String method, BigDecimal confidence, String displayName) {
    }

    /**
     * @param status     ASSOCIATED | UNMATCHED
     * @param reason     below_threshold | ambiguous | association_conflict | no_identity | restricted_method (UNMATCHED only)
     */
    public record Decision(String status, String employeeId, String method, BigDecimal confidence, String reason, List<Candidate> suggestions) {

        public boolean associated() {
            return "ASSOCIATED".equals(status);
        }
    }

    public static Decision decide(List<Candidate> candidates, boolean conflict, boolean restricted, BigDecimal autoFile, BigDecimal suggest) {
        List<Candidate> usable = new ArrayList<>();
        for (Candidate c : candidates) {
            if (!restricted || RESTRICTED_METHODS.contains(c.method())) {
                usable.add(c);
            }
        }
        if (conflict) {
            return new Decision("UNMATCHED", null, null, null, "association_conflict", suggestions(candidates, suggest));
        }
        Candidate winner = null;
        for (String method : PRIORITY) {
            winner = usable.stream().filter(c -> c.method().equals(method) && c.confidence().compareTo(autoFile) >= 0)
                    .max(Comparator.comparing(Candidate::confidence)).orElse(null);
            if (winner != null) {
                break;
            }
        }
        if (winner == null) {
            String reason = candidates.isEmpty() ? "no_identity" : restricted && usable.isEmpty() ? "restricted_method" : "below_threshold";
            return new Decision("UNMATCHED", null, null, null, reason, suggestions(restricted ? usable : candidates, suggest));
        }
        if ("AREA_MEASUREMENT".equals(winner.method())) {
            return new Decision("ASSOCIATED", null, winner.method(), winner.confidence(), null, List.of());
        }
        Candidate w = winner;
        boolean ambiguous = usable.stream().anyMatch(c -> c.employeeId() != null && !c.employeeId().equals(w.employeeId())
                && c.confidence().compareTo(w.confidence().subtract(AMBIGUITY)) >= 0);
        if (ambiguous) {
            return new Decision("UNMATCHED", null, null, null, "ambiguous", suggestions(usable, suggest));
        }
        return new Decision("ASSOCIATED", w.employeeId(), w.method(), w.confidence(), null, List.of());
    }

    /** Best candidate per employee at or above the suggest threshold, highest first. */
    static List<Candidate> suggestions(List<Candidate> candidates, BigDecimal suggest) {
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c.employeeId() == null || c.confidence().compareTo(suggest) < 0) {
                continue;
            }
            best.merge(c.employeeId(), c, (a, b) -> a.confidence().compareTo(b.confidence()) >= 0 ? a : b);
        }
        return best.values().stream().sorted(Comparator.comparing(Candidate::confidence).reversed()).toList();
    }

    /** Vendor-typed employee id normalisation: case, separators, configured prefixes and leading zeros. */
    public static String normaliseId(String raw, List<String> prefixes) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "").replace("_", "");
        for (String p : prefixes) {
            String up = p.toUpperCase(Locale.ROOT).replace("-", "");
            if (!up.isEmpty() && s.startsWith(up) && s.length() > up.length()) {
                s = s.substring(up.length());
                break;
            }
        }
        int i = 0;
        String letters = s.replaceAll("^([A-Z]*).*$", "$1");
        String digits = s.substring(letters.length());
        while (i < digits.length() - 1 && digits.charAt(i) == '0') {
            i++;
        }
        return letters + digits.substring(i);
    }

    /** Jaro-Winkler similarity (0–1) of two names, case- and accent-insensitive on letters only. */
    public static double jaroWinkler(String a, String b) {
        String s1 = letters(a);
        String s2 = letters(b);
        if (s1.isEmpty() || s2.isEmpty()) {
            return 0;
        }
        if (s1.equals(s2)) {
            return 1;
        }
        int range = Math.max(0, Math.max(s1.length(), s2.length()) / 2 - 1);
        boolean[] m1 = new boolean[s1.length()];
        boolean[] m2 = new boolean[s2.length()];
        int matches = 0;
        for (int i = 0; i < s1.length(); i++) {
            int from = Math.max(0, i - range);
            int to = Math.min(i + range + 1, s2.length());
            for (int j = from; j < to; j++) {
                if (!m2[j] && s1.charAt(i) == s2.charAt(j)) {
                    m1[i] = true;
                    m2[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int t = 0;
        int k = 0;
        for (int i = 0; i < s1.length(); i++) {
            if (m1[i]) {
                while (!m2[k]) {
                    k++;
                }
                if (s1.charAt(i) != s2.charAt(k)) {
                    t++;
                }
                k++;
            }
        }
        double m = matches;
        double jaro = (m / s1.length() + m / s2.length() + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(s1.length(), s2.length())); i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    static String letters(String s) {
        if (s == null) {
            return "";
        }
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll("\\s+", " ").strip();
    }
}
