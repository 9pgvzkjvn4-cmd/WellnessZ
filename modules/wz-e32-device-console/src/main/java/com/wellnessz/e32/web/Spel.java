package com.wellnessz.e32.web;

/** {@code @PreAuthorize} expressions of the E32 endpoints (contract §13) and path patterns. */
final class Spel {

    static final String DEVICE_ROLES = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS')";
    static final String CATALOGUE_READ = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','BIOMEDICAL','PLATFORM_ADMIN',"
            + "'AUDITOR','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD')";
    static final String CONFIG_READ = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','PLATFORM_ADMIN','AUDITOR')";
    static final String CONFIG_WRITE = "hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER')";
    static final String APPROVE = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','SECURITY_OPS')";
    static final String APPROVER = "hasAnyRole('DEVICE_APPROVER','SECURITY_OPS')";
    static final String NURSE_REVIEW = "hasAnyRole('OH_CLINICIAN','NURSING_HEAD')";
    /** The secret-free review view: the reviewers plus the console roles that read configurations anyway. */
    static final String REVIEW_READ = "hasAnyRole('OH_CLINICIAN','NURSING_HEAD','DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS',"
            + "'PLATFORM_ADMIN','AUDITOR')";
    static final String ADMIN = "hasRole('DEVICE_ADMIN')";
    static final String TESTER = "hasRole('INTEGRATION_TESTER')";
    static final String DRIVER_CERTIFY = "hasRole('DEVICE_APPROVER')";
    static final String DRIVER_DEPRECATE = "hasAnyRole('DEVICE_APPROVER','DEVICE_ADMIN')";
    static final String SECOPS = "hasRole('SECURITY_OPS')";
    static final String LIFECYCLE = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER')";
    static final String POSTURE = "hasAnyRole('DEVICE_ADMIN','SECURITY_OPS')";
    static final String CALIBRATION_READ = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','BIOMEDICAL','PLATFORM_ADMIN',"
            + "'AUDITOR','OH_CLINICIAN','OH_PHYSICIAN','AME','CMO','NURSING_HEAD','COLLECTOR')";
    static final String CALIBRATION_CHECK = "hasAnyRole('DEVICE_ADMIN','BIOMEDICAL','OH_CLINICIAN','OH_PHYSICIAN','AME','NURSING_HEAD','COLLECTOR')";
    static final String CALIBRATION_REPORT = "hasAnyRole('DEVICE_ADMIN','NURSING_HEAD','AUDITOR')";
    static final String MIGRATION = "hasAnyRole('DEVICE_ADMIN','BIOMEDICAL')";
    static final String FLEET = "hasAnyRole('DEVICE_ADMIN','SECURITY_OPS','NURSING_HEAD','PLATFORM_ADMIN')";
    static final String CONSUMABLES = "hasAnyRole('DEVICE_ADMIN','OH_CLINICIAN','NURSING_HEAD','BIOMEDICAL')";
    static final String WORKSPACE = "hasAnyRole('MANAGER','NURSING_HEAD')";
    static final String CHANGES = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','AUDITOR')";
    static final String DIAGNOSTICS = "hasAnyRole('DEVICE_ADMIN','INTEGRATION_TESTER','SECURITY_OPS')";
    static final String CERTIFICATE = "hasAnyRole('DEVICE_ADMIN','DEVICE_APPROVER','INTEGRATION_TESTER','SECURITY_OPS','PLATFORM_ADMIN','AUDITOR')";

    static final String CONFIG_ID = "CFG-[0-9]{6}";
    static final String INSTANCE_ID = "DEV-[0-9]{6}";
    static final String MODEL_CODE = "(MD-[0-9]{3}|WZ-[0-9]{6})";
    static final String CODE = "[A-Z0-9_]{2,40}";
    static final String SITE = "[A-Z0-9_*\\-]{1,40}";
    static final String PACKAGE_ID = "[a-z0-9]+(\\.[a-z0-9\\-]+){1,4}";
    static final String VERSION = "[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,5}";

    private Spel() {
    }
}
