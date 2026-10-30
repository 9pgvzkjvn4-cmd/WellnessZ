package com.wellnessz.e09.rules;

import java.util.Collection;

import com.wellnessz.e09.model.MentalHealthEnums.Rag;

/**
 * Psychosocial risk scoring (E09-F05, ISO 45003): factor score = likelihood × severity (each 1–5, so 1–25); RAG per score
 * (RED ≥ 15, AMBER ≥ 8, otherwise GREEN). The overall score of an assessment is its highest factor score – one red factor makes
 * the department red.
 */
public final class PsychosocialScoring {

    public static final int AMBER_FROM = 8;
    public static final int RED_FROM = 15;

    private PsychosocialScoring() {
    }

    public static int score(int likelihood, int severity) {
        if (likelihood < 1 || likelihood > 5 || severity < 1 || severity > 5) {
            throw new IllegalArgumentException("likelihood and severity must be 1..5");
        }
        return likelihood * severity;
    }

    public static Rag rag(int score) {
        if (score >= RED_FROM) {
            return Rag.RED;
        }
        return score >= AMBER_FROM ? Rag.AMBER : Rag.GREEN;
    }

    public static int overall(Collection<Integer> factorScores) {
        return factorScores.stream().mapToInt(Integer::intValue).max().orElse(0);
    }
}
