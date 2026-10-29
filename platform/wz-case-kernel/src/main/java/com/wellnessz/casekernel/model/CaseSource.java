package com.wellnessz.casekernel.model;

/** Entry point that opened the case (E01 controlled front door). */
public enum CaseSource {
    EHS_INCIDENT, SELF_REFERRAL, MANAGER_REFERRAL, HR_REFERRAL, WALK_IN, EMERGENCY, ABSENCE_TRIGGER, SURVEILLANCE_TRIGGER,
    FITNESS_DECLARATION, DA_TEST, SYSTEM_TRIGGER, PARTNER_REFERRAL
}
