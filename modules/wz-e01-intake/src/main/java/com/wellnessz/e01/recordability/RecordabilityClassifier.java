package com.wellnessz.e01.recordability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wellnessz.casekernel.model.CaseEnums.Recordability;

/**
 * OSHA 29 CFR 1904.7-style recordability logic (E01-HLR-07), used as a benchmark model and adapted to local law
 * through the mapping table: fatality &gt; days away (lost time) &gt; restricted work / transfer &gt; medical treatment
 * beyond first aid, loss of consciousness or a significant diagnosed condition &gt; first aid.
 */
public final class RecordabilityClassifier {

    public record Question(String key, String text, String help, String type) {
    }

    public static final List<Question> QUESTIONS = List.of(
            new Question("fatality", "Did the injury or illness result in death?", "Includes deaths occurring later from the event.", "BOOLEAN"),
            new Question("daysAwayFromWork", "How many calendar days away from work (excluding the day of injury)?",
                    "Count calendar days the employee could not work as recommended by a clinician.", "NUMBER"),
            new Question("restrictedWorkOrTransfer", "Was the employee restricted from routine functions or transferred to another job?",
                    "Includes modified duty and partial shifts recommended by a clinician.", "BOOLEAN"),
            new Question("medicalTreatmentBeyondFirstAid", "Was medical treatment beyond first aid given?",
                    "E.g. sutures, prescription medication, rigid splints, physiotherapy – not plasters, cold packs, eye flushing.", "BOOLEAN"),
            new Question("lossOfConsciousness", "Was there a loss of consciousness?", "Any duration.", "BOOLEAN"),
            new Question("significantDiagnosedCondition", "Was a significant injury or illness diagnosed by a clinician?",
                    "E.g. fracture, punctured eardrum, occupational cancer – even if not treated.", "BOOLEAN"));

    public static final Map<String, String> DEFINITIONS;

    static {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("FIRST_AID", "Only first-aid measures (non-prescription medication at non-prescription strength, cleaning, plasters, hot/cold therapy, eye flushing).");
        d.put("MEDICAL_TREATMENT", "Treatment beyond first aid, loss of consciousness or a significant diagnosed injury/illness, without days away or restriction.");
        d.put("RESTRICTED_DUTY", "Employee kept from performing routine job functions or transferred to another job.");
        d.put("LOST_TIME", "One or more calendar days away from work after the day of the event.");
        d.put("FATALITY", "The work-related injury or illness resulted in death.");
        DEFINITIONS = Map.copyOf(d);
    }

    public static final List<String> REASON_CODES = List.of("NEW_INFORMATION", "CLINICAL_REVIEW", "DATA_CORRECTION",
            "REGULATORY_GUIDANCE", "WORK_RELATEDNESS_DECISION", "OTHER");

    private RecordabilityClassifier() {
    }

    public static Recordability classify(Map<String, Object> answers) {
        if (bool(answers, "fatality")) {
            return Recordability.FATALITY;
        }
        if (num(answers, "daysAwayFromWork") > 0) {
            return Recordability.LOST_TIME;
        }
        if (bool(answers, "restrictedWorkOrTransfer")) {
            return Recordability.RESTRICTED_DUTY;
        }
        if (bool(answers, "medicalTreatmentBeyondFirstAid") || bool(answers, "lossOfConsciousness")
                || bool(answers, "significantDiagnosedCondition")) {
            return Recordability.MEDICAL_TREATMENT;
        }
        return Recordability.FIRST_AID;
    }

    private static boolean bool(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v instanceof Boolean b ? b : v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static double num(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? 0 : Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
