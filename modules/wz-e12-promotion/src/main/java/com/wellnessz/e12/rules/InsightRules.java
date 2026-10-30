package com.wellnessz.e12.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Classification and anonymisation of prevention insights (E12-F01): kernel cases, intake incidents and E03 results are
 * reduced to counts per org unit and indicator before anything leaves the service; each indicator series is suppressed with
 * {@link Suppression#counts}. Pure – unit tested.
 */
public final class InsightRules {

    public static final String MSK = "MSK";
    public static final String HEARING = "HEARING";
    public static final String METABOLIC = "METABOLIC";
    public static final String FATIGUE = "FATIGUE";
    public static final String MENTAL_WELLBEING = "MENTAL_WELLBEING";
    public static final Set<String> RISKS = Set.of(MSK, HEARING, METABOLIC, FATIGUE, MENTAL_WELLBEING);

    private static final List<String> MSK_TERMS = List.of("back", "neck", "shoulder", "knee", "wrist", "hand", "arm", "elbow", "hip",
            "ankle", "foot", "spine", "lumbar", "finger", "thumb", "leg", "upper limb", "lower limb");

    private InsightRules() {
    }

    /** Musculoskeletal body region (free-text body part from EHS / triage). */
    public static boolean isMsk(String bodyPart) {
        if (bodyPart == null || bodyPart.isBlank()) {
            return false;
        }
        String b = bodyPart.toLowerCase(Locale.ROOT);
        return MSK_TERMS.stream().anyMatch(b::contains);
    }

    public static boolean isNightShift(String shift) {
        return shift != null && shift.toUpperCase(Locale.ROOT).contains("NIGHT");
    }

    /** One counted observation (never person-level). */
    public record Count(String unitCode, String risk, String indicator, long value) {
    }

    /** A published insight row; {@code value} is {@code null} when suppressed. */
    public record Insight(String ref, String unitCode, String risk, String indicator, Long value, String period, boolean suppressed) {
    }

    /** Sums counts per (unit, risk, indicator), drops empty cells and applies suppression per indicator series. */
    public static List<Insight> publish(List<Count> counts, String period, int k) {
        Map<String, Map<String, Long>> byIndicator = new LinkedHashMap<>();
        Map<String, String> riskOf = new LinkedHashMap<>();
        for (Count c : counts) {
            if (c.unitCode() == null || c.value() <= 0) {
                continue;
            }
            String key = c.risk() + "|" + c.indicator();
            riskOf.put(key, c.risk());
            byIndicator.computeIfAbsent(key, x -> new LinkedHashMap<>()).merge(c.unitCode(), c.value(), Long::sum);
        }
        List<Insight> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> series : byIndicator.entrySet()) {
            String risk = riskOf.get(series.getKey());
            String indicator = series.getKey().substring(series.getKey().indexOf('|') + 1);
            List<String> units = new ArrayList<>(series.getValue().keySet());
            List<Long> values = units.stream().map(series.getValue()::get).toList();
            boolean[] hide = Suppression.counts(values, k);
            for (int i = 0; i < units.size(); i++) {
                out.add(new Insight(risk + ":" + units.get(i) + ":" + indicator, units.get(i), risk, indicator, hide[i] ? null : values.get(i),
                        period, hide[i]));
            }
        }
        out.sort(java.util.Comparator.comparing(Insight::risk).thenComparing(Insight::indicator).thenComparing(Insight::unitCode));
        return out;
    }
}
