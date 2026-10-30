package com.wellnessz.e11.web;

import com.wellnessz.common.model.Roles;

/** {@code @PreAuthorize} expressions and validation patterns of the E11 endpoints (contract §5). */
final class Spel {

    static final String OH = Roles.OH_TEAM_SPEL;
    static final String CLINICIANS = Roles.CLINICIANS_SPEL;
    static final String EMPLOYEE = "hasRole('EMPLOYEE')";
    static final String DESTINATION_READ = Roles.OH_TEAM_SPEL + " or hasRole('EMPLOYEE')";
    static final String DESTINATION_WRITE = "hasAnyRole('OH_PHYSICIAN','CMO')";
    static final String CAMPAIGNS = "hasAnyRole('NURSING_HEAD','OH_CLINICIAN')";
    static final String CAMPAIGN_INVITE = "hasRole('NURSING_HEAD')";
    static final String UPTAKE = "hasAnyRole('NURSING_HEAD','CMO')";
    static final String ADVISORY_WRITE = "hasAnyRole('OH_PHYSICIAN','CMO','AME')";
    static final String PUBLIC_HEALTH = "hasAnyRole('OH_PHYSICIAN','CMO')";

    static final String EMPLOYEE_ID = "[A-Za-z0-9_\\-]{1,64}";
    static final String CODE = "[A-Z0-9_]{2,40}";
    static final String COUNTRY = "[A-Z]{2}";

    private Spel() {
    }
}
