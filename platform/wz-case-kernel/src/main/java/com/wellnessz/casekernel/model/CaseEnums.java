package com.wellnessz.casekernel.model;

/** Small case enumerations used by intake (E01) and triage (E02). */
public final class CaseEnums {

    private CaseEnums() {
    }

    /** E02-HLR-04 queues "Awaiting external" / "Awaiting employee"; SLA timers pause while waiting. */
    public enum WaitingState { NONE, AWAITING_EXTERNAL, AWAITING_EMPLOYEE }

    /** OSHA-1904-style recordability (E01-HLR-07), adapted to local law through the mapping table. */
    public enum Recordability { NOT_CLASSIFIED, FIRST_AID, MEDICAL_TREATMENT, RESTRICTED_DUTY, LOST_TIME, FATALITY }

    public enum WorkRelated { YES, NO, UNDER_REVIEW }

    /** Case-management intensity driven by the complexity score (E02-F06). */
    public enum Intensity { STANDARD, INTENSIVE }
}
