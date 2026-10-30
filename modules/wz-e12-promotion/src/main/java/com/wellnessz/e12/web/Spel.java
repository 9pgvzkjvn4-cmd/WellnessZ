package com.wellnessz.e12.web;

/** {@code @PreAuthorize} expressions of the E12 endpoints (contract §6). */
final class Spel {

    static final String LEAD = "hasRole('HEALTH_PROMOTION_LEAD')";
    static final String INSIGHTS = "hasAnyRole('HEALTH_PROMOTION_LEAD','CMO','NURSING_HEAD')";
    static final String REPORT = "hasAnyRole('HEALTH_PROMOTION_LEAD','CMO')";
    static final String SCREENING_EVENTS = "hasAnyRole('HEALTH_PROMOTION_LEAD','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD')";
    static final String CLINICIANS = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN')";
    static final String EDUCATION = "hasAnyRole('HEALTH_PROMOTION_LEAD','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD')";
    static final String ERGO = "hasAnyRole('ERGONOMIST','HSE_OFFICER')";
    static final String ERGO_ACTION_UPDATE = "hasAnyRole('ERGONOMIST','HSE_OFFICER','MANAGER')";
    static final String MANAGER = "hasRole('MANAGER')";
    static final String EMPLOYEE = "hasRole('EMPLOYEE')";
    static final String INTEGRATION = "hasRole('INTEGRATION')";

    static final String UUID_RE = "[0-9a-fA-F\\-]{36}";

    private Spel() {
    }
}
