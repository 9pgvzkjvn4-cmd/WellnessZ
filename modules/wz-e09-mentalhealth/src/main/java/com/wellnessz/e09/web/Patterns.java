package com.wellnessz.e09.web;

/** Validation patterns and role expressions shared by the E09 request records and controllers. */
final class Patterns {

    static final String EMPLOYEE_ID = "[A-Za-z0-9_\\-]{1,64}";
    static final String USERNAME = "[A-Za-z0-9_.@\\-]{1,200}";
    static final String EMAIL = "[^@\\s]{1,100}@[^@\\s]{1,200}";
    static final String CODE = "[A-Z0-9_]{2,40}";
    static final String BASE = "[A-Za-z]{3}";
    static final String LANGUAGE = "[A-Za-z]{2,3}";
    static final String PHONE = "\\+?[0-9 ()\\-]{6,30}";
    static final String UNIT = "[A-Za-z0-9_\\-]{1,64}";
    static final String QUARTER = "\\d{4}-Q[1-4]";
    static final String CONTACT_CODE = "PS-[A-Z0-9]{4}-[A-Z0-9]{4}";
    static final String DUTY_REF = "[A-Za-z0-9_\\-]{1,60}";

    static final String STATUS = "OPEN|SCREENED|ACTIVE|REFERRED|RELIEF|CLOSED";
    static final String RISK = "LOW|MODERATE|HIGH|URGENT";
    static final String EAP_STATUS = "SENT|ACCEPTED|IN_PROGRESS|CLOSED";
    static final String URGENCY = "ROUTINE|PRIORITY";
    static final String CISM_RESPONSE = "ACCEPTED|DECLINED";
    static final String SESSION = "GROUP|INDIVIDUAL";
    static final String CONTACT = "PHONE|APP_CHAT|VIDEO";
    static final String RETURN_DECISION = "FIT|EXTEND";
    static final String ACTION_STATUS = "OPEN|DONE";
    static final String CONCERN = "WORK_STRESS|LOW_MOOD|ANXIETY|SLEEP|BEREAVEMENT|TRAUMA|SUBSTANCE|RELATIONSHIP|OTHER";
    static final String CLINICIAN_SOURCE = "CLINICIAN|PEER";
    static final String INSTRUMENT = "PHQ9|GAD7";
    static final String URGENT_OUTCOME = "REACHED_SAFE|REACHED_ESCALATED_EMERGENCY|NOT_REACHED_RETRY|NOT_REACHED_WELFARE_CHECK";
    static final String DECISION = "FIT|FIT_WITH_LIMITATIONS|TEMPORARILY_UNFIT|REFER_TO_AUTHORITY";
    static final String SHARING = "NOTHING|FITNESS_ONLY|FITNESS_AND_ADJUSTMENTS";
    static final String CLOSURE = "COMPLETED|DISENGAGED|REFERRED_ON";
    static final String PEER_CATEGORY = "STRESS|FATIGUE|RELATIONSHIP|BEREAVEMENT|SUBSTANCE|TRAUMA|OTHER";
    static final String PEER_OUTCOME = "SUPPORTED|SIGNPOSTED|REFERRED_TO_TRUSTED_PROFESSIONAL|EMERGENCY";
    static final String CISM_CATEGORY = "FATALITY|ASSAULT|FUME_EVENT|AIRCRAFT_ACCIDENT|SERIOUS_INJURY|EMERGENCY_LANDING|SERIOUS_INCIDENT|OTHER";
    static final String FACTOR = "WORKLOAD|SHIFT_FATIGUE|VIOLENCE_AGGRESSION|ISOLATION|CHANGE|ROLE_CLARITY|SUPPORT|RECOGNITION";

    static final String OH = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN'";
    static final String PSYCHOSOCIAL = "hasAnyRole('HSE_OFFICER','CMO','NURSING_HEAD','HR')";
    static final String CISM_CLINICAL = "hasAnyRole('PSYCH_CLINICIAN','OH_CLINICIAN')";

    private Patterns() {
    }
}
