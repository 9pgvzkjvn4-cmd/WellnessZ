package com.wellnessz.casekernel.model;

/** Triage urgency grading (E02-HLR-01), most urgent first. */
public enum Urgency {
    EMERGENCY, URGENT, SEMI_URGENT, ROUTINE, ADVICE;

    /** @return the more urgent of the two */
    public static Urgency max(Urgency a, Urgency b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
