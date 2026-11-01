package com.wellnessz.e31.terminology;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * OSHA 29 CFR 1910.95 Appendix F age correction of audiograms (tables F-1 male, F-2 female; ages 20–60, younger use 20 and
 * older use 60) and the standard threshold shift (STS): average shift at 2000, 3000 and 4000 Hz of ≥ 10 dB in either ear
 * relative to the baseline after subtracting the age correction difference.
 */
public final class OshaAgeCorrection {

    public static final List<String> STS_FREQUENCIES = List.of("2000", "3000", "4000");

    private OshaAgeCorrection() {
    }

    public record Row(String sex, int age, int hz1000, int hz2000, int hz3000, int hz4000, int hz6000) {

        public Integer at(String frequency) {
            return switch (frequency) {
                case "1000" -> hz1000;
                case "2000" -> hz2000;
                case "3000" -> hz3000;
                case "4000" -> hz4000;
                case "6000" -> hz6000;
                default -> null;
            };
        }

        public Map<String, Integer> asMap() {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("1000", hz1000);
            m.put("2000", hz2000);
            m.put("3000", hz3000);
            m.put("4000", hz4000);
            m.put("6000", hz6000);
            return m;
        }
    }

    public static int clampAge(int age) {
        return Math.max(20, Math.min(60, age));
    }

    /**
     * @param ear        current thresholds of one ear (frequency → dB HL)
     * @param baseline   baseline thresholds of the same ear
     * @param table      lookup (sex, age) → row
     * @return average shift at 2/3/4 kHz after age correction (positive = worse)
     */
    public static double ageCorrectedShift(Map<String, Double> ear, Map<String, Double> baseline, String sex, int ageNow, int ageBaseline,
                                           BiFunction<String, Integer, Row> table) {
        Row now = table.apply(sex, clampAge(ageNow));
        Row base = table.apply(sex, clampAge(ageBaseline));
        double sum = 0;
        for (String f : STS_FREQUENCIES) {
            double correction = now.at(f) - base.at(f);
            sum += ear.get(f) - baseline.get(f) - correction;
        }
        return sum / STS_FREQUENCIES.size();
    }

    public static boolean sts(double averageShift) {
        return averageShift >= 10.0;
    }
}
