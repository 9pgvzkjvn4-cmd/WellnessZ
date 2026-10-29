package com.wellnessz.e08.web;

/** Validation patterns and role expressions shared by the E08 request records and controllers. */
final class Patterns {

    static final String CODE = "[A-Z0-9_]{2,40}";
    static final String USERNAME = "[A-Za-z0-9_.@\\-]{1,200}";
    static final String EMAIL = "[^@\\s]{1,100}@[^@\\s]{1,200}";
    static final String POLICY = "[A-Za-z0-9_./\\-]{1,100}";
    static final String CLAIM_TYPE = "WORKERS_COMP|EMPLOYER_LIABILITY|GROUP_MEDICAL|THIRD_PARTY";
    static final String CLAIM_STATUS = "NOTIFIED|OPEN|UNDER_REVIEW|SETTLED|CLOSED|LITIGATED";
    static final String REPORT_STATUS = "DRAFT|SUBMITTED|ACKNOWLEDGED|REJECTED|LATE";
    static final String CHANNEL = "API|EMAIL|PORTAL";
    static final String ASSIGNEE = "CLAIMS|NURSE_CM|ADJUSTER";
    static final String CATEGORY = "MEDICAL|REHAB|INDEMNITY|ADMIN|SETTLEMENT|LEGAL";
    static final String KIND = "ESTIMATE|INVOICE|PAYMENT|RESERVE";
    static final String CURRENCY = "[A-Z]{3}";
    static final String GROUP_BY = "SUBSIDIARY|DEPARTMENT|COST_CENTRE";
    static final String MONTH = "\\d{4}-\\d{2}";
    static final String UNIT = "[A-Za-z0-9_\\-]{1,64}";
    static final String LEGAL_TYPE = "MEDICO_LEGAL_REPORT|EXPERT_OPINION|IME";
    static final String LEGAL_STATUS = "OPEN|IN_PROGRESS|REPORT_READY|CLOSED";
    static final String REF = "[A-Z]{2,5}-\\d{4}-\\d{6}";
    static final String ACK_REF = "[A-Za-z0-9_./:\\-]{1,100}";

    static final String OH = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN'";

    private Patterns() {
    }
}
