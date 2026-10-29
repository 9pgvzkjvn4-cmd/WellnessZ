package com.wellnessz.e14.web;

/** {@code @PreAuthorize} expressions of the E14 endpoints (contract §5). */
final class E14Roles {

    static final String OH = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','CASE_MANAGER','PSYCH_CLINICIAN'";
    static final String CLINICIANS = "'OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','PSYCH_CLINICIAN'";

    /** OH roles and schedulers: booking, slots, waitlist. */
    static final String BOOKING = "hasAnyRole(" + OH + ",'CLINIC_SCHEDULER')";
    /** + reception: diary, appointment lists, attendance, check-in, queue board. */
    static final String FRONT_DESK = "hasAnyRole(" + OH + ",'CLINIC_SCHEDULER','CLINIC_RECEPTION')";
    /** Site resources and templates (read). */
    static final String SITE_READ = "hasAnyRole(" + OH + ",'CLINIC_SCHEDULER','CLINIC_RECEPTION','BIOMEDICAL','OH_ADMIN')";
    static final String CATALOGUE_ADMIN = "hasAnyRole('OH_ADMIN','NURSING_HEAD')";
    static final String TEMPLATE_WRITE = "hasAnyRole('OH_ADMIN','NURSING_HEAD','CLINIC_SCHEDULER')";
    static final String CHARGING = "hasAnyRole('OH_ADMIN','CMO')";
    static final String CLINICIAN = "hasAnyRole(" + CLINICIANS + ")";
    static final String CAMPAIGN_READ = "hasAnyRole(" + OH + ",'CLINIC_SCHEDULER','OH_ADMIN')";
    static final String CAMPAIGN_WRITE = "hasAnyRole('CLINIC_SCHEDULER','NURSING_HEAD','OH_ADMIN')";
    static final String CAMPAIGN_OPS = "hasAnyRole('CLINIC_SCHEDULER','NURSING_HEAD')";
    static final String EQUIPMENT_READ = "hasAnyRole(" + OH + ",'BIOMEDICAL','OH_ADMIN','CLINIC_SCHEDULER')";
    static final String EQUIPMENT_WRITE = "hasAnyRole('BIOMEDICAL','OH_ADMIN')";
    static final String BIOMEDICAL = "hasRole('BIOMEDICAL')";
    static final String CALIBRATION_DUE = "hasAnyRole('BIOMEDICAL','OH_ADMIN','NURSING_HEAD')";
    static final String READINGS_WRITE = "hasAnyRole(" + CLINICIANS + ",'BIOMEDICAL')";
    static final String EXCURSIONS_READ = "hasAnyRole(" + CLINICIANS + ",'BIOMEDICAL')";
    static final String EXCURSIONS_WRITE = "hasAnyRole(" + CLINICIANS + ")";
    static final String COLD_CHAIN_REPORT = "hasAnyRole(" + CLINICIANS + ",'BIOMEDICAL')";
    static final String CAPACITY = "hasAnyRole('NURSING_HEAD','CMO','OH_ADMIN','CLINIC_SCHEDULER')";

    private E14Roles() {
    }
}
