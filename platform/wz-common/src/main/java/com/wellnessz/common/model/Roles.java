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
    // increment 2 (E03 / E05)
    /** Data protection officer: break-glass review, disclosure and retention reports; no clinical read. */
    public static final String DPO = "DPO";

    // increment 3 (E06 / E07 / E14)
    /** Clinic scheduler: diaries, bookings, campaigns, waitlists; demographics and appointment type only, no clinical read. */
    public static final String CLINIC_SCHEDULER = "CLINIC_SCHEDULER";
    /** Biomedical / equipment technician: equipment register, calibration and cold-chain logs; no clinical read. */
    public static final String BIOMEDICAL = "BIOMEDICAL";
    /** Crew training & licensing: crew medical certificate validity and expiry (status only). */
    public static final String CREW_LICENSING = "CREW_LICENSING";
    /** Pre-employment candidate (not yet an employee): own questionnaire, consent, documents and booking only. */
    public static final String CANDIDATE = "CANDIDATE";
    // increment 4 (E04 / E08)
    /** Insurance / claims coordinator: claim records, e-reporting, costs, consent-gated disclosure sets; no clinical notes. */
    public static final String CLAIMS_COORDINATOR = "CLAIMS_COORDINATOR";
    /** External TPA / insurer adjuster (partner portal, MFA): consented documents, RTW milestones, shared tasks of own claims only. */
    public static final String TPA_ADJUSTER = "TPA_ADJUSTER";
    /** Legal counsel: litigation hold, privilege marking, medico-legal requests and released reports of litigated claims. */
    public static final String LEGAL_COUNSEL = "LEGAL_COUNSEL";
    /** Finance partner: cost roll-ups by unit / cost centre and ERP export; no person-level clinical data. */
    public static final String FINANCE = "FINANCE";
    /** Procurement: provider network directory, contracts, rate cards and SLAs. */
    public static final String PROCUREMENT = "PROCUREMENT";
    // increment 5 (E09 / E10 / E11 / E12)
    /** Trained peer volunteer of the crew support programme: own contact log entries only, never identifiable to the operator. */
    public static final String PEER_SUPPORT_VOLUNTEER = "PEER_SUPPORT_VOLUNTEER";
    /** Peer-support programme coordinator: volunteer register, training, availability, aggregated programme figures. */
    public static final String PEER_SUPPORT_COORDINATOR = "PEER_SUPPORT_COORDINATOR";
    /** Safety (SMS) manager: anonymised programme and exposure reports, CISM events, post-incident test requirements. */
    public static final String SAFETY_MANAGER = "SAFETY_MANAGER";
    /** Drug & alcohol programme manager: pools, policies, random selections, coverage – never individual results. */
    public static final String DA_PROGRAMME_MANAGER = "DA_PROGRAMME_MANAGER";
    /** Medical review officer: verifies laboratory results, prescription checks, split specimens. */
    public static final String MRO = "MRO";
    /** Specimen collection staff: electronic chain-of-custody forms – no results. */
    public static final String COLLECTOR = "COLLECTOR";
    /** Health promotion lead: campaign planning, screening events, participation analytics (anonymised). */
    public static final String HEALTH_PROMOTION_LEAD = "HEALTH_PROMOTION_LEAD";
    /** Ergonomist: workstation and manual-handling assessments, actions to closure. */
    public static final String ERGONOMIST = "ERGONOMIST";
    /** Roles of the Occupational Health clinical team (clinical disclosure). */
    public static final String[] OH_TEAM = {OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, CASE_MANAGER, PSYCH_CLINICIAN};
    /** Roles that may record a fitness-for-duty decision. */
    public static final String[] FITNESS_DECIDERS = {OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, PSYCH_CLINICIAN};
    /** OH team members who document clinically (excludes the case-management-only role). */
    public static final String[] CLINICIANS = {OH_CLINICIAN, OH_PHYSICIAN, AME, CMO, NURSING_HEAD, PSYCH_CLINICIAN};
    /** Physicians (orders, sign-off of licensed-role fitness decisions). */
    public static final String[] PHYSICIANS = {OH_PHYSICIAN, AME, CMO};
    /** SpEL fragment for {@code @PreAuthorize}. */
    public static final String OH_TEAM_SPEL = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN')";

    public static final String CLINICIANS_SPEL = "hasAnyRole('OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN')";
    public static final String PHYSICIANS_SPEL = "hasAnyRole('OH_PHYSICIAN','AME','CMO')";

    private Roles() {
    }
}
