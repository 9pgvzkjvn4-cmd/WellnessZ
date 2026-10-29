package com.wellnessz.security.consent;

/** Purposes for which explicit employee consent is captured (GDPR Art. 9(2)(a) / local equivalents). */
public enum ConsentPurpose {
    /** Share clinical information with partner hospitals, specialists, physio. */
    TREATMENT_SHARING_PARTNER,
    /** Referral to the employee assistance programme. */
    EAP_REFERRAL,
    /** Share claim-relevant information with insurers / TPAs. */
    INSURER_SHARING,
    /** Ingest data from consented wearables. */
    WEARABLE_DATA,
    /** Participate in health-promotion campaigns. */
    HEALTH_CAMPAIGNS,
    /** Use of de-identified data for research / predictive models. */
    RESEARCH_DEIDENTIFIED,
    /** AI-assisted documentation of a consultation (ambient capture / dictation), E03-HLR-07. */
    AI_DOCUMENTATION,
    /** Video / secure-chat consultation with an OH clinician or partner specialist (E04-F07). */
    TELEHEALTH_CONSULTATION
}
