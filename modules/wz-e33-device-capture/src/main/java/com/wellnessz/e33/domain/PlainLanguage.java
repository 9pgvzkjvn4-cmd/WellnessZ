package com.wellnessz.e33.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Plain-language result summaries for employees (E33-HLR-09, E33-F12) and the kiosk (E33-BL-013) in the employee's preferred
 * language (E19 languages: English, Arabic, French; English is the fallback). Category text only – blood-pressure range
 * (ESH 2018 office categories), BMI band (WHO), a neutral sentence for every other test – never a diagnosis, always the
 * pointer to the occupational health team. The reading itself is the employee's own data and is shown to them.
 */
public final class PlainLanguage {

    public static final List<String> LANGUAGES = List.of("en", "ar", "fr");

    /** Summary text and an education link (E29 / E30 nudge) when one applies. */
    public record Summary(String language, String text, String educationLink) {
    }

    private static final Map<String, String[]> T = Map.ofEntries(
            Map.entry("bp", new String[]{
                "Your blood pressure was %s mmHg. This is in the %s range.",
                "كان ضغط دمك %s ملم زئبق. هذه القراءة ضمن نطاق %s.",
                "Votre tension artérielle était de %s mmHg, ce qui se situe dans la zone %s."}),
            Map.entry("bp.optimal", new String[]{"optimal", "المثالي", "optimale"}),
            Map.entry("bp.normal", new String[]{"normal", "الطبيعي", "normale"}),
            Map.entry("bp.high-normal", new String[]{"high-normal", "الطبيعي المرتفع", "normale haute"}),
            Map.entry("bp.raised", new String[]{"raised", "المرتفع", "élevée"}),
            Map.entry("bmi", new String[]{
                "Your body mass index (BMI) was %s, which is %s.",
                "كان مؤشر كتلة جسمك %s، وهو %s.",
                "Votre indice de masse corporelle (IMC) était de %s, ce qui est %s."}),
            Map.entry("bmi.below", new String[]{"below the healthy range", "أقل من النطاق الصحي", "en dessous de la zone de poids santé"}),
            Map.entry("bmi.healthy", new String[]{"within the healthy range", "ضمن النطاق الصحي", "dans la zone de poids santé"}),
            Map.entry("bmi.above", new String[]{"above the healthy range", "أعلى من النطاق الصحي", "au-dessus de la zone de poids santé"}),
            Map.entry("bmi.well-above", new String[]{"well above the healthy range", "أعلى بكثير من النطاق الصحي",
                "nettement au-dessus de la zone de poids santé"}),
            Map.entry("generic", new String[]{
                "Your %s on %s has been recorded and reviewed by your occupational health team.",
                "تم تسجيل %s بتاريخ %s ومراجعته من قبل فريق الصحة المهنية.",
                "Votre %s du %s a été enregistré et examiné par votre équipe de santé au travail."}),
            Map.entry("closing", new String[]{
                "A single reading is not a diagnosis. Your occupational health team will contact you if any follow-up is needed.",
                "القراءة الواحدة ليست تشخيصاً. سيتواصل معك فريق الصحة المهنية إذا لزمت أي متابعة.",
                "Une mesure isolée n'est pas un diagnostic. Votre équipe de santé au travail vous contactera si un suivi est nécessaire."}),
            Map.entry("test.AUDIOMETRY", new String[]{"hearing test", "اختبار السمع", "test auditif"}),
            Map.entry("test.SPIROMETRY", new String[]{"lung function test", "اختبار وظائف الرئة", "test de la fonction respiratoire"}),
            Map.entry("test.VISION", new String[]{"vision test", "اختبار النظر", "test de vision"}),
            Map.entry("test.VITALS", new String[]{"health check", "الفحص الصحي", "bilan de santé"}),
            Map.entry("test.BODY_COMPOSITION", new String[]{"body measurement", "قياس الجسم", "mesure corporelle"}),
            Map.entry("test.OTHER", new String[]{"health check", "الفحص الصحي", "bilan de santé"}));

    private static final Map<String, String> EDUCATION = Map.of(
            "bp", "/health-checks/learn/blood-pressure",
            "bmi", "/health-checks/learn/healthy-weight",
            "AUDIOMETRY", "/health-checks/learn/protect-your-hearing",
            "SPIROMETRY", "/health-checks/learn/healthy-lungs");

    private PlainLanguage() {
    }

    public static String language(String preferred) {
        return preferred != null && LANGUAGES.contains(preferred.toLowerCase()) ? preferred.toLowerCase() : "en";
    }

    /** ESH 2018 office blood-pressure category key: optimal, normal, high-normal, raised. */
    public static String bpCategory(BigDecimal systolic, BigDecimal diastolic) {
        int s = systolic.intValue();
        int d = diastolic.intValue();
        if (s >= 140 || d >= 90) {
            return "raised";
        }
        if (s >= 130 || d >= 85) {
            return "high-normal";
        }
        if (s >= 120 || d >= 80) {
            return "normal";
        }
        return "optimal";
    }

    /** WHO adult BMI band key: below, healthy, above (25–29.9), well-above (≥ 30). */
    public static String bmiBand(BigDecimal bmi) {
        double b = bmi.doubleValue();
        if (b < 18.5) {
            return "below";
        }
        if (b < 25) {
            return "healthy";
        }
        return b < 30 ? "above" : "well-above";
    }

    /** BMI from weight (kg) and height (cm), one decimal; {@code null} when either is missing or implausible. */
    public static BigDecimal bmi(BigDecimal weightKg, BigDecimal heightCm) {
        if (weightKg == null || heightCm == null || heightCm.signum() <= 0 || weightKg.signum() <= 0) {
            return null;
        }
        BigDecimal m = heightCm.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        return weightKg.divide(m.multiply(m), 1, RoundingMode.HALF_UP);
    }

    /**
     * @param values component key → numeric value (keys {@code systolic}, {@code diastolic}, {@code bmi}, {@code weightKg},
     *               {@code heightCm}); other keys are ignored
     */
    public static Summary summarise(String category, Map<String, BigDecimal> values, LocalDate measuredOn, String preferredLanguage) {
        String lang = language(preferredLanguage);
        int i = LANGUAGES.indexOf(lang);
        List<String> parts = new ArrayList<>();
        String link = null;
        BigDecimal sys = values.get("systolic");
        BigDecimal dia = values.get("diastolic");
        if (sys != null && dia != null) {
            parts.add(String.format(T.get("bp")[i], sys.stripTrailingZeros().toPlainString() + "/" + dia.stripTrailingZeros().toPlainString(),
                    T.get("bp." + bpCategory(sys, dia))[i]));
            link = EDUCATION.get("bp");
        }
        BigDecimal bmi = values.containsKey("bmi") ? values.get("bmi") : bmi(values.get("weightKg"), values.get("heightCm"));
        if (bmi != null) {
            parts.add(String.format(T.get("bmi")[i], bmi.setScale(1, RoundingMode.HALF_UP).toPlainString(), T.get("bmi." + bmiBand(bmi))[i]));
            link = link == null ? EDUCATION.get("bmi") : link;
        }
        if (parts.isEmpty()) {
            String[] test = T.getOrDefault("test." + category, T.get("test.OTHER"));
            parts.add(String.format(T.get("generic")[i], test[i], measuredOn));
            link = EDUCATION.get(category);
        }
        parts.add(T.get("closing")[i]);
        return new Summary(lang, String.join(" ", parts), link);
    }
}
