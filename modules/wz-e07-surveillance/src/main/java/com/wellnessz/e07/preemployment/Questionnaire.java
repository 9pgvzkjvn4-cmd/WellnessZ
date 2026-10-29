package com.wellnessz.e07.preemployment;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.wellnessz.common.error.ApiException;

/** Pre-employment health questionnaire (fixed question set; answers are stored encrypted and shown to OH only). */
public final class Questionnaire {

    public record Question(String code, String text, String type, boolean required) {
    }

    public static final List<Question> QUESTIONS = List.of(
            new Question("ONGOING_TREATMENT", "Are you currently receiving any treatment or under the care of a doctor?", "YES_NO", true),
            new Question("ONGOING_TREATMENT_DETAILS", "If yes, please give details", "TEXT", false),
            new Question("TIME_OFF_12M", "Have you had more than two weeks off work for health reasons in the last 12 months?", "YES_NO", true),
            new Question("ADJUSTMENTS_NEEDED", "Do you need any adjustments or support to carry out the role or attend the assessment?", "YES_NO", true),
            new Question("ADJUSTMENTS_DETAILS", "If yes, which adjustments would help?", "TEXT", false),
            new Question("HEARING_VISION", "Do you have any difficulty with your hearing or eyesight (with correction if worn)?", "YES_NO", true),
            new Question("SKIN_OR_CHEST", "Have you had any skin or chest condition that was made worse by work?", "YES_NO", true),
            new Question("SHIFT_WORK", "Is there any reason you could not work shifts or nights if the role requires it?", "YES_NO", true),
            new Question("LAST_VACCINATION_RECORD", "Date of your most recent vaccination record (if known)", "DATE", false),
            new Question("DECLARATION", "I confirm that the information I have given is complete and accurate", "YES_NO", true));

    private static final Map<String, Question> BY_CODE = new LinkedHashMap<>();

    static {
        QUESTIONS.forEach(q -> BY_CODE.put(q.code(), q));
    }

    private Questionnaire() {
    }

    /** Validates answers against the question set; returns the normalised answers. */
    public static Map<String, String> validate(Map<String, String> answers) {
        if (answers == null || answers.isEmpty() || answers.size() > QUESTIONS.size()) {
            throw ApiException.badRequest("invalid_answers", "Answer the questionnaire");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> a : answers.entrySet()) {
            Question q = BY_CODE.get(a.getKey());
            if (q == null) {
                throw ApiException.badRequest("unknown_question", "Unknown question code");
            }
            String v = a.getValue() == null ? null : a.getValue().strip();
            if (v == null || v.isEmpty()) {
                continue;
            }
            switch (q.type()) {
                case "YES_NO" -> {
                    if (!"YES".equals(v) && !"NO".equals(v)) {
                        throw ApiException.badRequest("invalid_answer", "Answer YES or NO to " + q.code());
                    }
                }
                case "DATE" -> {
                    try {
                        LocalDate.parse(v);
                    } catch (DateTimeParseException e) {
                        throw ApiException.badRequest("invalid_answer", "Use YYYY-MM-DD for " + q.code());
                    }
                }
                default -> {
                    if (v.length() > 2000) {
                        throw ApiException.badRequest("invalid_answer", "Answer too long for " + q.code());
                    }
                }
            }
            out.put(q.code(), v);
        }
        for (Question q : QUESTIONS) {
            if (q.required() && !out.containsKey(q.code())) {
                throw ApiException.badRequest("answer_required", "Please answer " + q.code());
            }
        }
        if (!"YES".equals(out.get("DECLARATION"))) {
            throw ApiException.badRequest("declaration_required", "Please confirm the declaration");
        }
        return out;
    }
}
