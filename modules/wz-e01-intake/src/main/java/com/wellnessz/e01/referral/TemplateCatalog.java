package com.wellnessz.e01.referral;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Department intake templates (E01-BL-009) with multilingual labels (E01-BL-010: en / ar / fr).
 * Content is a clinically reviewable DRAFT – sign-off by the Nursing Head is a governance step (E18 versioning).
 */
@Component
public class TemplateCatalog {

    public enum Audience { EMPLOYEE, MANAGER, HR }

    public enum FieldType { TEXT, TEXTAREA, SELECT, MULTISELECT, BOOLEAN, DATE, BODY_MAP, SYMPTOMS }

    public record Option(String value, String label) {
    }

    public record Field(String key, String label, FieldType type, boolean required, List<Option> options, Integer maxLength) {
    }

    public record Template(String code, String title, Audience audience, List<String> departments, List<Field> fields,
                           String routingQueue, List<Option> symptomOptions) {
    }

    public record Language(String code, String label) {
    }

    public static final List<Language> LANGUAGES = List.of(new Language("en", "English"), new Language("ar", "العربية"),
            new Language("fr", "Français"));

    /** i18n: key → {en, ar, fr}. */
    private static final Map<String, String[]> L = new LinkedHashMap<>();

    private static void l(String key, String en, String ar, String fr) {
        L.put(key, new String[]{en, ar, fr});
    }

    static {
        l("t.CATERING", "Catering – cuts, burns, food-handler symptoms", "التموين – جروح وحروق وأعراض متداولي الأغذية", "Restauration – coupures, brûlures, symptômes manipulateur d'aliments");
        l("t.RAMP_CARGO", "Ramp & cargo – manual handling, MSK, heat", "الساحة والشحن – المناولة اليدوية والعضلات والحرارة", "Piste & fret – manutention, TMS, chaleur");
        l("t.DUTYFREE_DISTRIBUTION", "Duty free & distribution – MSK, slips and trips", "السوق الحرة والتوزيع – العضلات والانزلاق", "Duty free & distribution – TMS, glissades");
        l("t.ENGINEERING", "Engineering – chemical and noise exposure", "الهندسة – التعرض للمواد الكيميائية والضوضاء", "Ingénierie – exposition chimique et bruit");
        l("t.CORPORATE", "Corporate – ergonomics and wellbeing", "الإدارة – بيئة العمل والرفاه", "Siège – ergonomie et bien-être");
        l("t.CREW", "Flight & cabin crew – fatigue, wellbeing, fitness", "طاقم الطائرة والمقصورة – الإرهاق والرفاه واللياقة", "Équipage – fatigue, bien-être, aptitude");
        l("t.MANAGER_WORK_IMPACT", "Manager referral – work impact only", "إحالة المدير – أثر العمل فقط", "Orientation par le manager – impact sur le travail uniquement");
        l("t.HR_ABSENCE", "HR referral – absence and fitness advice", "إحالة الموارد البشرية – الغياب والمشورة", "Orientation RH – absence et avis d'aptitude");
        l("f.bodyParts", "Where is the problem? (body map)", "أين المشكلة؟ (خريطة الجسم)", "Où se situe le problème ? (carte du corps)");
        l("f.symptoms", "Symptoms", "الأعراض", "Symptômes");
        l("f.howHappened", "How did it happen?", "كيف حدث ذلك؟", "Comment est-ce arrivé ?");
        l("f.atWork", "Did this happen at work?", "هل حدث هذا في العمل؟", "Est-ce arrivé au travail ?");
        l("f.firstAidGiven", "Was first aid given?", "هل تم تقديم الإسعافات الأولية؟", "Des premiers soins ont-ils été donnés ?");
        l("f.handledFood", "Have you handled food in the last 48 hours?", "هل تعاملت مع الطعام خلال 48 ساعة الماضية؟", "Avez-vous manipulé des aliments ces 48 dernières heures ?");
        l("f.loadKg", "Approximate load handled (kg)", "الحمل التقريبي (كغ)", "Charge manipulée approximative (kg)");
        l("f.substance", "Substance or noise source involved", "المادة أو مصدر الضوضاء", "Substance ou source de bruit");
        l("f.ppeUsed", "Was PPE used?", "هل استُخدمت معدات الوقاية؟", "Des EPI étaient-ils utilisés ?");
        l("f.workstation", "Workstation / setup concern", "مشكلة في محطة العمل", "Problème de poste de travail");
        l("f.lastDuty", "Last duty (date)", "آخر مناوبة (تاريخ)", "Dernier service (date)");
        l("f.nextDuty", "Next rostered duty (date)", "المناوبة التالية (تاريخ)", "Prochain service (date)");
        l("f.fatigueScore", "Fatigue level", "مستوى الإرهاق", "Niveau de fatigue");
        l("f.dutiesAffected", "Duties affected", "المهام المتأثرة", "Tâches concernées");
        l("f.startDate", "Since when", "منذ متى", "Depuis quand");
        l("f.safetyConcern", "Is there a safety concern?", "هل يوجد قلق يتعلق بالسلامة؟", "Y a-t-il un problème de sécurité ?");
        l("f.adjustmentsTried", "Adjustments already tried", "التعديلات التي تمت تجربتها", "Aménagements déjà essayés");
        l("f.questionsForOh", "Questions for Occupational Health", "أسئلة للصحة المهنية", "Questions pour la santé au travail");
        l("f.absenceSummary", "Absence summary (dates, pattern)", "ملخص الغياب", "Résumé des absences");
        l("f.policyStage", "Absence policy stage", "مرحلة سياسة الغياب", "Étape de la politique d'absence");
        l("o.YES", "Yes", "نعم", "Oui");
        l("o.NO", "No", "لا", "Non");
        l("o.UNSURE", "Not sure", "غير متأكد", "Pas sûr");
        l("o.LOW", "Low", "منخفض", "Faible");
        l("o.MODERATE", "Moderate", "متوسط", "Modéré");
        l("o.SEVERE", "Severe", "شديد", "Sévère");
        l("o.INFORMAL", "Informal", "غير رسمي", "Informel");
        l("o.STAGE_1", "Stage 1", "المرحلة 1", "Étape 1");
        l("o.STAGE_2", "Stage 2", "المرحلة 2", "Étape 2");
        l("o.FINAL", "Final", "نهائية", "Finale");
        for (String[] s : new String[][]{
                {"PAIN", "Pain", "ألم", "Douleur"}, {"SWELLING", "Swelling", "تورم", "Gonflement"}, {"BLEEDING", "Bleeding", "نزيف", "Saignement"},
                {"BURN", "Burn", "حرق", "Brûlure"}, {"NUMBNESS", "Numbness / tingling", "خدر", "Engourdissement"},
                {"STIFFNESS", "Stiffness", "تيبس", "Raideur"}, {"DIARRHOEA", "Diarrhoea", "إسهال", "Diarrhée"},
                {"VOMITING", "Vomiting", "قيء", "Vomissements"}, {"FEVER", "Fever", "حمى", "Fièvre"},
                {"SKIN_LESION", "Skin rash / lesion", "طفح جلدي", "Lésion cutanée"}, {"COUGH", "Cough / breathing", "سعال / تنفس", "Toux / respiration"},
                {"HEADACHE", "Headache", "صداع", "Maux de tête"}, {"DIZZINESS", "Dizziness", "دوخة", "Vertiges"},
                {"HEARING", "Hearing change / ringing", "تغير في السمع", "Troubles auditifs"}, {"EYE_IRRITATION", "Eye irritation", "تهيج العين", "Irritation oculaire"},
                {"FATIGUE", "Fatigue", "إرهاق", "Fatigue"}, {"STRESS", "Stress / low mood", "توتر / مزاج منخفض", "Stress / humeur basse"},
                {"SLEEP", "Sleep problems", "مشاكل النوم", "Troubles du sommeil"}}) {
            l("s." + s[0], s[1], s[2], s[3]);
        }
    }

    private static final Map<String, List<String>> SYMPTOMS = Map.of(
            "CATERING", List.of("PAIN", "BLEEDING", "BURN", "DIARRHOEA", "VOMITING", "FEVER", "SKIN_LESION"),
            "RAMP_CARGO", List.of("PAIN", "SWELLING", "STIFFNESS", "NUMBNESS", "DIZZINESS", "HEADACHE"),
            "DUTYFREE_DISTRIBUTION", List.of("PAIN", "SWELLING", "STIFFNESS", "BLEEDING"),
            "ENGINEERING", List.of("COUGH", "EYE_IRRITATION", "SKIN_LESION", "HEARING", "HEADACHE", "BURN"),
            "CORPORATE", List.of("PAIN", "STIFFNESS", "HEADACHE", "EYE_IRRITATION", "STRESS", "SLEEP"),
            "CREW", List.of("FATIGUE", "SLEEP", "STRESS", "HEADACHE", "DIZZINESS", "PAIN", "COUGH"));

    private static final Map<String, List<String>> DEPARTMENTS = Map.of(
            "CATERING", List.of("CATERING"), "RAMP_CARGO", List.of("GROUND", "CARGO"),
            "DUTYFREE_DISTRIBUTION", List.of("DUTYFREE", "DISTRIBUTION"), "ENGINEERING", List.of("ENG"),
            "CORPORATE", List.of("CORP", "AIRLINE", "OPS"), "CREW", List.of("CABIN", "FLIGHTOPS"));

    private static final Map<String, String> ROUTING = Map.of("CATERING", "FOOD_SAFETY", "CREW", "AME_AEROMEDICAL",
            "RAMP_CARGO", "OH_GENERAL", "DUTYFREE_DISTRIBUTION", "OH_GENERAL", "ENGINEERING", "OH_GENERAL", "CORPORATE", "OH_GENERAL",
            "MANAGER_WORK_IMPACT", "OH_GENERAL", "HR_ABSENCE", "OH_GENERAL");

    public List<Template> templates(Audience audience, String lang) {
        int i = idx(lang);
        List<Template> out = new ArrayList<>();
        if (audience == Audience.EMPLOYEE) {
            for (String code : List.of("CATERING", "RAMP_CARGO", "DUTYFREE_DISTRIBUTION", "ENGINEERING", "CORPORATE", "CREW")) {
                out.add(employeeTemplate(code, i));
            }
        } else if (audience == Audience.MANAGER) {
            out.add(new Template("MANAGER_WORK_IMPACT", t("t.MANAGER_WORK_IMPACT", i), audience, List.of(), List.of(
                    f("dutiesAffected", FieldType.TEXTAREA, true, i, 1000), f("startDate", FieldType.DATE, true, i, null),
                    yesNo("safetyConcern", true, i), f("adjustmentsTried", FieldType.TEXTAREA, false, i, 1000),
                    f("questionsForOh", FieldType.TEXTAREA, false, i, 1000)), ROUTING.get("MANAGER_WORK_IMPACT"), List.of()));
        } else {
            out.add(new Template("HR_ABSENCE", t("t.HR_ABSENCE", i), audience, List.of(), List.of(
                    f("absenceSummary", FieldType.TEXTAREA, true, i, 1000),
                    new Field("policyStage", t("f.policyStage", i), FieldType.SELECT, true,
                            opts(i, "INFORMAL", "STAGE_1", "STAGE_2", "FINAL"), null),
                    f("questionsForOh", FieldType.TEXTAREA, false, i, 1000)), ROUTING.get("HR_ABSENCE"), List.of()));
        }
        return out;
    }

    public Optional<Template> find(String code, String lang) {
        return java.util.stream.Stream.of(Audience.values()).flatMap(a -> templates(a, lang).stream())
                .filter(t -> t.code().equals(code)).findFirst();
    }

    /** Suggested template for a department (employee app pre-selection). */
    public Optional<String> templateForDepartment(String departmentCode) {
        return DEPARTMENTS.entrySet().stream().filter(e -> e.getValue().contains(departmentCode)).map(Map.Entry::getKey).findFirst();
    }

    public boolean isSymptom(String code) {
        return L.containsKey("s." + code);
    }

    private Template employeeTemplate(String code, int i) {
        List<Field> fields = new ArrayList<>(List.of(
                new Field("bodyParts", t("f.bodyParts", i), FieldType.BODY_MAP, false, List.of(), null),
                new Field("symptoms", t("f.symptoms", i), FieldType.SYMPTOMS, true, symptomOptions(code, i), null),
                yesNo("atWork", true, i)));
        switch (code) {
            case "CATERING" -> {
                fields.add(yesNo("handledFood", true, i));
                fields.add(yesNo("firstAidGiven", false, i));
            }
            case "RAMP_CARGO", "DUTYFREE_DISTRIBUTION" -> {
                fields.add(f("howHappened", FieldType.TEXTAREA, true, i, 1000));
                fields.add(f("loadKg", FieldType.TEXT, false, i, 10));
            }
            case "ENGINEERING" -> {
                fields.add(f("substance", FieldType.TEXT, true, i, 200));
                fields.add(yesNo("ppeUsed", true, i));
            }
            case "CORPORATE" -> fields.add(f("workstation", FieldType.TEXTAREA, false, i, 1000));
            case "CREW" -> {
                fields.add(f("lastDuty", FieldType.DATE, false, i, null));
                fields.add(f("nextDuty", FieldType.DATE, false, i, null));
                fields.add(new Field("fatigueScore", t("f.fatigueScore", i), FieldType.SELECT, false, opts(i, "LOW", "MODERATE", "SEVERE"), null));
            }
            default -> {
                // no extra fields
            }
        }
        return new Template(code, t("t." + code, i), Audience.EMPLOYEE, DEPARTMENTS.get(code), fields, ROUTING.get(code),
                symptomOptions(code, i));
    }

    private static List<Option> symptomOptions(String code, int i) {
        return SYMPTOMS.getOrDefault(code, List.of()).stream().map(s -> new Option(s, t("s." + s, i))).toList();
    }

    private static Field f(String key, FieldType type, boolean required, int i, Integer max) {
        return new Field(key, t("f." + key, i), type, required, List.of(), max);
    }

    private static Field yesNo(String key, boolean required, int i) {
        return new Field(key, t("f." + key, i), FieldType.SELECT, required, opts(i, "YES", "NO", "UNSURE"), null);
    }

    private static List<Option> opts(int i, String... values) {
        return java.util.Arrays.stream(values).map(v -> new Option(v, t("o." + v, i))).toList();
    }

    static String t(String key, int i) {
        String[] v = L.get(key);
        return v == null ? key : v[i];
    }

    static int idx(String lang) {
        String l = lang == null ? "en" : lang.toLowerCase(Locale.ROOT);
        return switch (l) {
            case "ar" -> 1;
            case "fr" -> 2;
            default -> 0;
        };
    }
}
