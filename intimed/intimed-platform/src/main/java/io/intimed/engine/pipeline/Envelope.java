package io.intimed.engine.pipeline;

import java.time.Instant;

/**
 * Acquired device message. {@code mode}: LIVE (published configuration), TEST (exchange harness – delivered into sandbox
 * template instances), SHADOW (parallel run before clinical sign-off – delivered into shadow copies).
 */
public record Envelope(String tenant, String endpointId, String configId, String option, String payload, String mode,
                       String testSubject, String runId, String remote, Instant receivedAt) {
    public static Envelope live(String tenant, String endpointId, String configId, String option, String payload, String remote) {
        return new Envelope(tenant, endpointId, configId, option, payload, "LIVE", null, null, remote, Instant.now());
    }

    public Envelope withConfig(String cfg) { return new Envelope(tenant, endpointId, cfg, option, payload, mode, testSubject, runId, remote, receivedAt); }
    public boolean test() { return "TEST".equals(mode); }
}
