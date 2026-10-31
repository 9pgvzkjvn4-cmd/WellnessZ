/**
 * E31 – Medical device integration engine – pipeline, adapters, normalisation & routing (increment 6, schema {@code deviceint}, Flyway V21).
 *
 * <p>Pipeline (acquire → parse → normalise → associate → validate → route → persist → acknowledge), protocol adapters P1–P10,
 * driver-package runtime, terminology (MDC → LOINC / UCUM), association, quality gates, routing through
 * {@link com.wellnessz.casekernel.device.DeviceObservationSink}, FHIR observation store with hash-linked raw archive,
 * simulators, golden datasets and connector observability. Implements {@code DeviceObservationGateway} and
 * {@code DeviceDiagnostics}; owns {@code /api/v1/device-ingest/**} and the routing sink adapters in E03 / E07 / E10 / E12.</p>
 *
 * <p>Boundaries (ArchitectureTest): depends on the platform and {@code com.wellnessz.casekernel.device} only; never on
 * another device module or on a business module. Binding contract: {@code docs/increment-06/API-Contract-E31-E34.md}.</p>
 */
package com.wellnessz.e31;
