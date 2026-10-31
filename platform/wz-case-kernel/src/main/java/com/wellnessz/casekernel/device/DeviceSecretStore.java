package com.wellnessz.casekernel.device;

import java.util.Optional;

/**
 * Vault for connector secrets (E34-HLR-05): vendor-database credentials, OAuth2 client secrets, webhook HMAC secrets,
 * DICOM / MLLP TLS key passwords. Implemented by security (E34) on top of wz-secrets (field encryption with the master key;
 * an external vault / KMS reference {@code vault:<path>} is resolved the same way). Configuration and driver packages hold
 * only the reference {@code devsec:<uuid>}. Contract section 15.6.
 */
public interface DeviceSecretStore {

    /** Stores a secret and returns its reference; the value is never returned to a browser again. */
    String store(String actor, String scope, String name, char[] value);

    /**
     * Resolves a reference for runtime use by the engine (E31) only – every resolution is audited with the purpose.
     *
     * @param purpose e.g. {@code CLOUD_POLL}, {@code WEBHOOK_HMAC}, {@code VENDOR_DB_READ}
     */
    Optional<char[]> resolve(String reference, String purpose);

    void destroy(String actor, String reference);

    /** Replaces the value behind a reference (rotation); the reference stays the same. */
    void rotate(String actor, String reference, char[] newValue);
}
