/**
 * E32 – Device catalogue, administration & connectivity provisioning console (increment 6, schema {@code devicecat}, Flyway V23).
 *
 * <p>Catalogue (133-model seed from the device inventory), option packs P1–P10, eight-step provisioning wizard, prerequisites,
 * configuration with four-eyes approval and change history, bindings, certification PDF, publish, fleet, calibration / QC
 * and lockout, driver registry, vendor posture, request-a-model, diagnostics (via {@code DeviceDiagnostics}) and
 * decommissioning. Implements {@code DeviceCatalogueLookup} and {@code DeviceEndpointStatusListener}.</p>
 *
 * <p>Boundaries (ArchitectureTest): depends on the platform and {@code com.wellnessz.casekernel.device} only; never on
 * another device module or on a business module. Binding contract: {@code docs/increment-06/API-Contract-E31-E34.md}.</p>
 */
package com.wellnessz.e32;
