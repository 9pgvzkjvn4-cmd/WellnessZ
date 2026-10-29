package com.wellnessz.e04.web;

/** {@code @PreAuthorize} expressions of the E04 endpoints (contract §3). */
final class Spel {

    static final String OH = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN'";
    static final String CLIN = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN'";
    static final String OH_TEAM = "hasAnyRole(" + OH + ")";
    static final String CLINICIANS = "hasAnyRole(" + CLIN + ")";
    static final String CLINICIANS_OR_CLAIMS = "hasAnyRole(" + CLIN + ",'CLAIMS_COORDINATOR')";
    static final String OH_OR_CLAIMS = "hasAnyRole(" + OH + ",'CLAIMS_COORDINATOR')";
    static final String CLAIMS_FINANCE = "hasAnyRole('CLAIMS_COORDINATOR','FINANCE')";
    static final String PROVIDER_READ = "hasAnyRole(" + OH + ",'PROCUREMENT','CLAIMS_COORDINATOR')";
    static final String SCORECARDS = "hasAnyRole('CMO','NURSING_HEAD','PROCUREMENT')";
    static final String PARTNER = "hasRole('PARTNER_PROVIDER')";
    static final String ADJUSTER = "hasRole('TPA_ADJUSTER')";
    static final String EMPLOYEE = "hasRole('EMPLOYEE')";
    static final String INTEGRATION = "hasRole('INTEGRATION')";

    static final String EMP_ID = "[A-Za-z0-9_\\-]{1,64}";
    static final String CODE = "[A-Z][A-Z0-9_]{1,39}";
    static final String ORG = "[A-Z0-9_]{2,40}";
    static final String MONTH = "\\d{4}-(0[1-9]|1[0-2])";

    private Spel() {
    }
}
