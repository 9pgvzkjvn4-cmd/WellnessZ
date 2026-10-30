package com.wellnessz.e10.web;

/** Validation patterns shared by the E10 request records and controllers. */
final class Patterns {

    static final String ROLE_GROUP = "FLIGHT_CREW|CABIN_CREW|AIRSIDE_DRIVER|ENGINEER|FORKLIFT|SECURITY";
    static final String PANEL = "DRUGS_5|DRUGS_10|ALCOHOL_BREATH";
    static final String TEST_TYPE = "DRUGS|ALCOHOL";
    static final String TEST_STATUS = "REQUIRED|NOTIFIED|DISPATCHED|COLLECTED|IN_LAB|MRO_REVIEW|VERIFIED_NEGATIVE|VERIFIED_POSITIVE|REFUSAL|CANCELLED|WINDOW_MISSED";
    static final String VERDICT = "NEGATIVE|NEGATIVE_LEGITIMATE_PRESCRIPTION|POSITIVE|POSITIVE_DILUTE|REFUSAL_ADULTERATED|REFUSAL_SUBSTITUTED|CANCELLED";
    static final String APPROVAL = "PENDING|APPROVED";
    static final String EXCLUSION = "LEAVE|LONG_TERM_ABSENCE|NOT_SAFETY_SENSITIVE";
    static final String OBSERVATION = "ODOUR_OF_ALCOHOL|SLURRED_SPEECH|UNSTEADY_GAIT|ERRATIC_BEHAVIOUR|DROWSINESS|DILATED_PUPILS|ADMISSION|OTHER";
    static final String SPECIMEN_TYPE = "URINE|ORAL_FLUID|BREATH";
    static final String CUSTODY_EVENT = "SEALED|COURIER_PICKUP|LAB_RECEIVED";
    static final String REFUSAL = "NO_SHOW|REFUSED|ADULTERATION_ATTEMPT|SUBSTITUTION_SUSPECTED";
    static final String VALIDITY = "VALID|DILUTE|ADULTERATED|SUBSTITUTED|INVALID";
    static final String OUTCOME = "NEGATIVE|NON_NEGATIVE";
    static final String PLAN_STATUS = "DRAFT|ACTIVE|RTD_PENDING|MONITORING|COMPLETED|CANCELLED";
    static final String EVALUATION = "READY|NOT_READY";
    static final String PERIOD = "\\d{4}-(0[1-9]|1[0-2])";
    static final String EMPLOYEE_ID = "[A-Za-z0-9_\\-]{1,64}";
    static final String JOB_CODE = "[A-Z0-9_]{2,40}";
    static final String SPECIMEN_ID = "[A-Za-z0-9\\-]{4,40}";
    static final String SITE = "[A-Za-z0-9_\\-]{2,40}";
    static final String LAB_REF = "[A-Za-z0-9_./:\\-]{1,100}";
    static final String ANALYTE = "[A-Z0-9_\\-]{2,40}";
    static final String NUMBER = "[<>]?\\d{1,9}(\\.\\d{1,6})?";
    static final String UNIT = "[A-Za-z0-9/%µ ]{1,20}";
    static final String FORMAT = "pdf|csv";

    private Patterns() {
    }
}
