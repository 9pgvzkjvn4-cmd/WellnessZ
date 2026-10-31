/**
 * E34 – Endpoint & integration security (increment 6, schema {@code devicesec}, Flyway V24).
 *
 * <p>Endpoint identities (certificate thumbprint + hashed per-endpoint token), enrolment, zoning, package signatures,
 * connector secrets, tamper-evident security events with SIEM outbox, anomaly rules and 15-minute isolation, revocation,
 * vendor posture policy and the security test pack. Implements {@code DeviceEndpointAuthenticator},
 * {@code DeviceSecurityEventPublisher}, {@code DeviceEndpointProvisioning}, {@code DevicePackageSignatures} and
 * {@code DeviceSecretStore}; owns {@code /api/v1/device-enrolment/**}.</p>
 *
 * <p>Boundaries (ArchitectureTest): depends on the platform and {@code com.wellnessz.casekernel.device} only; never on
 * another device module or on a business module. Binding contract: {@code docs/increment-06/API-Contract-E31-E34.md}.</p>
 */
package com.wellnessz.e34;
