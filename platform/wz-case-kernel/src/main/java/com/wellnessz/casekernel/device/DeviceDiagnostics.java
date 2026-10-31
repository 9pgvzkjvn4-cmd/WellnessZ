package com.wellnessz.casekernel.device;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Engine-side diagnostics, health and test runs, implemented by the engine (E31) and used by the console (E32): masked
 * live trace (E32-HLR-09), connector health for the fleet dashboard (E32-F05), wizard step 6 connection / end-to-end tests
 * against a simulator or a live device (E32-BL-005), and "re-send last result". Everything returned is PHI-masked.
 * Contract sections 12 and 13.
 */
public interface DeviceDiagnostics {

    /** Last messages of a device instance, newest first, PHI masked (names, ids, dates of birth, values replaced). */
    List<TraceEntry> trace(String deviceInstanceId, int limit);

    ConnectorHealth health(String deviceInstanceId);

    /** Runs a connection + end-to-end test of a (draft or published) configuration; synchronous for simulator mode. */
    TestRun runTest(String actor, TestRequest request);

    /** Re-processes the last accepted raw message of the instance through the pipeline (idempotency still applies). */
    UUID resendLast(String actor, String deviceInstanceId);

    /**
     * @param stage    ACQUIRE | PARSE | NORMALISE | ASSOCIATE | VALIDATE | ROUTE | PERSIST | ACK
     * @param outcome  OK | WARN | ERROR | DEAD_LETTER
     */
    record TraceEntry(UUID messageId, Instant receivedAt, String stage, String outcome, String code, String maskedExcerpt,
                      String mappingOutcome, Long clockOffsetMillis) {
    }

    /** @param state HEALTHY | DEGRADED | STALE | SILENT | ISOLATED */
    record ConnectorHealth(String deviceInstanceId, String state, Instant lastSeenAt, long messages24h, long errors24h,
                           Long p95LatencyMillis, long unmatchedOpen, long deadLetters) {
    }

    /**
     * @param configurationId E32 configuration (draft) id the test is for
     * @param mode            SIMULATOR | LIVE
     * @param goldenDatasetId simulator dataset, e.g. {@code spirometry/ndd-easyone-gdt/normal-adult}
     * @param parameters      non-secret option parameters of the draft (secrets by reference)
     */
    record TestRequest(String configurationId, String modelCode, DeviceCodes.Option option, String mode, String driverPackageId,
                       String driverVersion, String goldenDatasetId, Map<String, Object> parameters, String formCode) {

        public TestRequest {
            parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        }
    }

    /** @param status PASSED | FAILED */
    record TestRun(UUID testRunId, String status, List<TestStep> steps, String evidenceSha256, Instant finishedAt) {

        public TestRun {
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    /** @param outcome PASS | FAIL | SKIPPED */
    record TestStep(String name, String outcome, String detail) {
    }
}
