package com.wellnessz.e03.cds;

/**
 * Clinical decision support alert (E03-F05). Codes: ALLERGY_CLASS, INTERACTION, DUPLICATE_TEST, SURVEILLANCE_DUE,
 * DURATION_EXCEEDED, plus the informational PROTOCOL_PROMPT and FITNESS_STANDARD shown on encounters.
 */
public record CdsAlert(String code, String severity, String message, String reference) {

    public static final String ALLERGY_CLASS = "ALLERGY_CLASS";
    public static final String INTERACTION = "INTERACTION";
    public static final String DUPLICATE_TEST = "DUPLICATE_TEST";
    public static final String SURVEILLANCE_DUE = "SURVEILLANCE_DUE";
    public static final String DURATION_EXCEEDED = "DURATION_EXCEEDED";
    public static final String PROTOCOL_PROMPT = "PROTOCOL_PROMPT";
    public static final String FITNESS_STANDARD = "FITNESS_STANDARD";
}
