package io.intimed.security.auth;

import java.util.EnumSet;
import java.util.Set;

/** Roles of architecture §2.2 with their boundaries. MFA is mandatory for privileged roles. */
public enum Role {
    CLINICIAN("Clinician / Nurse / Technician", false),
    PHYSICIAN("Physician", false),
    PATIENT("Patient / Employee (kiosk, own data)", false),
    DEVICE_ADMIN("Device Administrator", true),
    TEMPLATE_OWNER("Template Owner", false),
    INTEGRATION_DEV("Integration Developer / Tester", false),
    PARTNER_DEV("Host-product / Partner Developer", false),
    SECURITY_ANALYST("Security Analyst / SOC", true),
    CISO_DPO("CISO / DPO", true),
    PLATFORM_SRE("Platform SRE", true),
    TENANT_ADMIN("Tenant Administrator", true),
    REGULATORY("Product / Regulatory / Quality", false),
    AUDITOR("Auditor / Regulator (read-only)", false);

    private final String label;
    private final boolean mfa;

    Role(String label, boolean mfa) { this.label = label; this.mfa = mfa; }

    public String label() { return label; }
    public boolean mfaRequired() { return mfa; }

    /** Roles that may never read clinical content (observations, sandbox instances with values). */
    public static final Set<Role> NO_CLINICAL = EnumSet.of(DEVICE_ADMIN, SECURITY_ANALYST, CISO_DPO, PLATFORM_SRE, TENANT_ADMIN, REGULATORY, PARTNER_DEV, INTEGRATION_DEV);
}
