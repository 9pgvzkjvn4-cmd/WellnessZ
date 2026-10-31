package com.wellnessz.casekernel.device;

/**
 * Driver-package signatures (E31-HLR-03, E34-HLR-06), implemented by security (E34): verification at import / publish
 * (E32) and at load (E31), signing of packages authored in the console with the platform signing key (Ed25519, JDK).
 * Signatures cover the canonical manifest JSON without the {@code signature} member. Contract sections 6 and 15.5.
 */
public interface DevicePackageSignatures {

    Verification verify(byte[] canonicalManifest, String signatureBase64, String keyId);

    /** Signs with the active platform package-signing key; the actor needs INTEGRATION_TESTER or DEVICE_APPROVER. */
    Signature sign(String actor, byte[] canonicalManifest);

    /** @param failureCode unknown_key | key_revoked | signature_invalid | algorithm_not_allowed */
    record Verification(boolean valid, String keyId, String algorithm, String failureCode) {
    }

    /** @param algorithm {@code Ed25519} (default) or {@code SHA256withECDSA} */
    record Signature(String keyId, String algorithm, String signatureBase64) {
    }
}
