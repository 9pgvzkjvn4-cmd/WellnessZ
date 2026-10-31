/**
 * E33 – Module-embedded device capture (increment 6, schema {@code devicecap}, Flyway V23).
 *
 * <p>Capture sessions bound to OH forms (form-binding API), station-aware device lists, preview with quality flags,
 * accept / reject / re-test, fall-back chain (another device → image/OCR → manual with attestation), batch and kiosk modes,
 * comparison views and employee summaries. Implements {@code DeviceCaptureSessions} and {@code DeviceObservationListener};
 * uses {@code DeviceObservationGateway} and {@code DeviceCatalogueLookup}.</p>
 *
 * <p>Boundaries (ArchitectureTest): depends on the platform and {@code com.wellnessz.casekernel.device} only; never on
 * another device module or on a business module. Binding contract: {@code docs/increment-06/API-Contract-E31-E34.md}.</p>
 */
package com.wellnessz.e33;
