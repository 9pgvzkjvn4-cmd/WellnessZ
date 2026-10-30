package com.wellnessz.e09.rules;

import java.util.List;

import com.wellnessz.e09.model.MentalHealthEnums.Band;
import com.wellnessz.e09.model.MentalHealthEnums.Instrument;
import com.wellnessz.e09.model.MentalHealthEnums.Risk;

/**
 * Scoring of the validated screens under clinical governance (E09-F02): PHQ-9 (9 items, 0–27) and GAD-7 (7 items, 0–21), each
 * item answered 0–3. Standard published severity bands. Urgent-risk rule (E09-BL-004): PHQ-9 item 9 (thoughts of self-harm)
 * answered above 0, or a SEVERE band, is URGENT – a same-day call back by a named clinician.
 */
public final class Screening {

    public record Result(Instrument instrument, int score, Band band, boolean urgent, Risk risk) {
    }

    private Screening() {
    }

    public static int itemCount(Instrument instrument) {
        return instrument == Instrument.PHQ9 ? 9 : 7;
    }

    /** @throws IllegalArgumentException when the number of answers or an answer value is out of range */
    public static Result score(Instrument instrument, List<Integer> answers) {
        if (answers == null || answers.size() != itemCount(instrument)) {
            throw new IllegalArgumentException("expected " + itemCount(instrument) + " answers");
        }
        int score = 0;
        for (Integer a : answers) {
            if (a == null || a < 0 || a > 3) {
                throw new IllegalArgumentException("answers must be 0..3");
            }
            score += a;
        }
        Band band = band(instrument, score);
        boolean urgent = urgent(instrument, answers, band);
        return new Result(instrument, score, band, urgent, risk(band, urgent));
    }

    public static Band band(Instrument instrument, int score) {
        if (score < 5) {
            return Band.MINIMAL;
        }
        if (score < 10) {
            return Band.MILD;
        }
        if (score < 15) {
            return Band.MODERATE;
        }
        if (instrument == Instrument.GAD7) {
            return Band.SEVERE;
        }
        return score < 20 ? Band.MODERATELY_SEVERE : Band.SEVERE;
    }

    public static boolean urgent(Instrument instrument, List<Integer> answers, Band band) {
        boolean item9 = instrument == Instrument.PHQ9 && answers.size() == 9 && answers.get(8) != null && answers.get(8) > 0;
        return item9 || band == Band.SEVERE;
    }

    public static Risk risk(Band band, boolean urgent) {
        if (urgent) {
            return Risk.URGENT;
        }
        return switch (band) {
            case MODERATELY_SEVERE, SEVERE -> Risk.HIGH;
            case MODERATE -> Risk.MODERATE;
            default -> Risk.LOW;
        };
    }

    /** Next steps shown to the employee – no interpretation of the score beyond the band. */
    public static List<String> nextSteps(Result r, String emergencyPhone) {
        if (r.urgent()) {
            return List.of("A member of the confidential support team will call you today.",
                    "If you are in immediate danger, call " + emergencyPhone + " now or go to the nearest emergency department.",
                    "24-hour lines are listed under signposting.");
        }
        return switch (r.band()) {
            case MODERATELY_SEVERE, SEVERE, MODERATE -> List.of("We recommend a confidential conversation – you can self-refer below.",
                    "The employee assistance programme offers free counselling.");
            case MILD -> List.of("Have a look at the signposting resources.", "You can self-refer at any time if things do not improve.");
            default -> List.of("Thank you – you can repeat the questionnaire at any time.", "Signposting resources are available if you need them.");
        };
    }
}
