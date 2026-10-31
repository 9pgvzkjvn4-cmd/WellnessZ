package com.wellnessz.e33.web;

/** {@code @PreAuthorize} expressions of the E33 endpoints (contract 14). The services apply ABAC and the restricted rules. */
final class Spel {

    static final String CLINICIANS = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN')";
    static final String CAPTURE = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN','COLLECTOR')";
    static final String SESSION_READ = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN','COLLECTOR','MRO')";
    static final String FORMS = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN','COLLECTOR','MRO',"
            + "'DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER')";
    static final String PHYSICIANS = "hasAnyRole('OH_PHYSICIAN','AME','CMO')";
    static final String KPIS = "hasAnyRole('NURSING_HEAD','CMO','DEVICE_ADMIN','PLATFORM_ADMIN')";
    static final String EMPLOYEE = "hasRole('EMPLOYEE')";
    static final String DEVICE_ENDPOINT = "hasRole('DEVICE_ENDPOINT')";

    static final String ID = "[A-Za-z0-9][A-Za-z0-9_\\-]{0,63}";
    static final String CODE = "[A-Z0-9][A-Z0-9_.\\-]{0,59}";

    private Spel() {
    }
}
