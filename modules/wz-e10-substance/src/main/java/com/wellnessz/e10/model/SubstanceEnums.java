package com.wellnessz.e10.model;

import java.util.EnumSet;
import java.util.Set;

/** Enumerations of the substance-testing domain (contract §4). Stored as their names. */
public final class SubstanceEnums {

    public enum RoleGroup { FLIGHT_CREW, CABIN_CREW, AIRSIDE_DRIVER, ENGINEER, FORKLIFT, SECURITY }

    public enum Reason { PRE_EMPLOYMENT, RANDOM, POST_INCIDENT, FOR_CAUSE, RETURN_TO_DUTY, FOLLOW_UP }

    public enum Panel { DRUGS_5, DRUGS_10, ALCOHOL_BREATH;

        public boolean alcohol() {
            return this == ALCOHOL_BREATH;
        }
    }

    public enum TestType { DRUGS, ALCOHOL }

    public enum TestStatus {
        REQUIRED, NOTIFIED, DISPATCHED, COLLECTED, IN_LAB, MRO_REVIEW, VERIFIED_NEGATIVE, VERIFIED_POSITIVE, REFUSAL, CANCELLED, WINDOW_MISSED;

        /** Awaiting collection (a collector can act on it). */
        public static final Set<TestStatus> PENDING_COLLECTION = EnumSet.of(REQUIRED, NOTIFIED, DISPATCHED);
        /** Open (not yet verified or closed). */
        public static final Set<TestStatus> OPEN = EnumSet.of(REQUIRED, NOTIFIED, DISPATCHED, COLLECTED, IN_LAB, MRO_REVIEW);
        /** Completed with a verified outcome (counts towards coverage and statistics). */
        public static final Set<TestStatus> COMPLETED = EnumSet.of(VERIFIED_NEGATIVE, VERIFIED_POSITIVE, REFUSAL);
    }

    public enum Verdict { NEGATIVE, NEGATIVE_LEGITIMATE_PRESCRIPTION, POSITIVE, POSITIVE_DILUTE, REFUSAL_ADULTERATED, REFUSAL_SUBSTITUTED, CANCELLED }

    public enum DutyStatus { AVAILABLE, REMOVED, RETURN_PENDING }

    public enum ApprovalStatus { PENDING, APPROVED }

    public enum ExclusionReason { LEAVE, LONG_TERM_ABSENCE, NOT_SAFETY_SENSITIVE }

    public enum Observation { ODOUR_OF_ALCOHOL, SLURRED_SPEECH, UNSTEADY_GAIT, ERRATIC_BEHAVIOUR, DROWSINESS, DILATED_PUPILS, ADMISSION, OTHER }

    public enum SpecimenType { URINE, ORAL_FLUID, BREATH }

    public enum CustodyEvent { COLLECTED, SEALED, COURIER_PICKUP, LAB_RECEIVED, SPLIT_ORDERED }

    public enum RefusalReason { NO_SHOW, REFUSED, ADULTERATION_ATTEMPT, SUBSTITUTION_SUSPECTED }

    public enum Validity { VALID, DILUTE, ADULTERATED, SUBSTITUTED, INVALID }

    public enum Outcome { NEGATIVE, NON_NEGATIVE }

    public enum PlanStatus { DRAFT, ACTIVE, RTD_PENDING, MONITORING, COMPLETED, CANCELLED }

    public enum EvaluationOutcome { READY, NOT_READY }

    private SubstanceEnums() {
    }
}
