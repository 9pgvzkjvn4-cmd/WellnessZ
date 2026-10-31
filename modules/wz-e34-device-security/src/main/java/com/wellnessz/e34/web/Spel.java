package com.wellnessz.e34.web;

/** {@code @PreAuthorize} expressions of the E34 endpoints (contract §15). Console roles never see person data. */
final class Spel {

    static final String SECOPS = "hasRole('SECURITY_OPS')";
    static final String SECOPS_OR_ADMIN = "hasAnyRole('SECURITY_OPS','DEVICE_ADMIN')";
    static final String EVENTS_READ = "hasAnyRole('SECURITY_OPS','AUDITOR')";
    static final String KEYS_READ = "hasAnyRole('SECURITY_OPS','INTEGRATION_TESTER')";
    static final String SIGNERS = "hasAnyRole('INTEGRATION_TESTER','DEVICE_APPROVER','SECURITY_OPS')";
    static final String VERIFIERS = "hasAnyRole('SECURITY_OPS','INTEGRATION_TESTER','DEVICE_ADMIN','DEVICE_APPROVER')";
    static final String DPIA_READ = "hasAnyRole('DPO','SECURITY_OPS','AUDITOR')";
    static final String DPO = "hasRole('DPO')";

    static final String ENDPOINT_ID = "[A-Za-z0-9._\\-]{1,64}";
    static final String CODE = "[A-Z][A-Z0-9_]{2,39}";
    static final String UUID_RE = "[0-9a-fA-F\\-]{36}";

    private Spel() {
    }
}
