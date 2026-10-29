package com.wellnessz.common.model;

/** Canonical application roles. Identity providers map their roles / groups onto these names. */
public final class Roles {
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String MANAGER = "MANAGER";
    public static final String HR = "HR";
    public static final String OH_CLINICIAN = "OH_CLINICIAN";
    public static final String OH_ADMIN = "OH_ADMIN";
    public static final String CASE_MANAGER = "CASE_MANAGER";
    public static final String PSYCH_CLINICIAN = "PSYCH_CLINICIAN";
    public static final String AUDITOR = "AUDITOR";
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String INTEGRATION = "INTEGRATION";
    public static final String PARTNER_PROVIDER = "PARTNER_PROVIDER";
    // increment 1 (E01 / E02)
    public static final String OH_PHYSICIAN = "OH_PHYSICIAN";
    public static final String AME = "AME";
    public static final String CMO = "CMO";
    public static final String NURSING_HEAD = "NURSING_HEAD";
    public static final String HSE_OFFICER = "HSE_OFFICER";
    public static final String CREW_SCHEDULING = "CREW_SCHEDULING";
    public static final String HYGIENE_MANAGER = "HYGIENE_MANAGER";
    public static final String CLINIC_RECEPTION = "CLINIC_RECEPTION";
    public static final String FIRST_AIDER = "FIRST_AIDER";

    /** Roles of the Occupational Health clinical team (clinical disclosure). */
    public static final String[] OH_TEAM = {OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, CASE_MANAGER, PSYCH_CLINICIAN};
    /** Roles that may record a fitness-for-duty decision. */
    public static final String[] FITNESS_DECIDERS = {OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, PSYCH_CLINICIAN};
    /** SpEL fragment for {@code @PreAuthorize}. */
    public static final String OH_TEAM_SPEL = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')";

    private Roles() {
    }
}
