package com.wellnessz.e07.web;

/** {@code @PreAuthorize} expressions of the E07 endpoints (contract §4). */
final class Spel {

    static final String OH = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN'";
    static final String OH_TEAM = "hasAnyRole(" + OH + ")";
    static final String CLINICIANS = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN')";
    static final String PHYSICIANS = "hasAnyRole('OH_PHYSICIAN','AME','CMO')";
    static final String PROGRAMME_READ = "hasAnyRole(" + OH + ",'OH_ADMIN','HSE_OFFICER','AUDITOR')";
    static final String PROGRAMME_WRITE = "hasAnyRole('OH_ADMIN','CMO','NURSING_HEAD')";
    static final String DUE_READ = "hasAnyRole(" + OH + ",'CLINIC_SCHEDULER')";
    static final String COMPLIANCE = "hasAnyRole(" + OH + ",'OH_ADMIN','HSE_OFFICER','AUDITOR')";
    static final String AUDIT_PACKS = "hasAnyRole('CMO','NURSING_HEAD','AUDITOR','HSE_OFFICER')";
    static final String CREW_STATUS = "hasAnyRole('AME','CMO','CREW_LICENSING','CREW_SCHEDULING')";

    private Spel() {
    }
}
