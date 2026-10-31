package com.wellnessz.e34.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Hash chain, canonical form and PHI minimisation of security events. */
class SecurityEventChainTest {

    @Test
    void attributesAreAllowListedSanitisedAndCapped() {
        Map<String, String> in = new HashMap<>();
        in.put("remoteAddress", "10.0.0.5");
        in.put("employeeId", "E1001");          // person identifier – dropped
        in.put("value", "120/80");              // measurement – dropped
        in.put("name", "Jane Doe");             // name – dropped
        in.put("path", "/api/v1/device-ingest/hl7v2\r\nInjected: yes");
        in.put("rate", "x".repeat(500));
        Map<String, String> out = SecurityEventChain.minimise(in);
        assertThat(out).containsOnlyKeys("remoteAddress", "path", "rate");
        assertThat(out.get("path")).doesNotContain("\r").doesNotContain("\n");
        assertThat(out.get("rate").length()).isLessThanOrEqualTo(201);
        assertThat(String.join(",", out.values())).doesNotContain("E1001").doesNotContain("Jane").doesNotContain("120/80");
    }

    @Test
    void typesAndModulesAreValidated() {
        assertThat(SecurityEventChain.safeType("ENDPOINT_AUTH_FAILED")).isEqualTo("ENDPOINT_AUTH_FAILED");
        assertThat(SecurityEventChain.safeType("drop table")).isEqualTo("INVALID_EVENT_TYPE");
        assertThat(SecurityEventChain.safeType(null)).isEqualTo("INVALID_EVENT_TYPE");
        assertThat(SecurityEventChain.safeModule("E31")).isEqualTo("E31");
        assertThat(SecurityEventChain.safeModule("X")).isEqualTo("KERNEL");
    }

    @Test
    void chainDetectsAnyChange() {
        Instant t = Instant.parse("2026-10-31T10:00:00.123Z");
        String c1 = SecurityEventChain.canonical("id1", "ENDPOINT_AUTH_FAILED", "MEDIUM", "EP-1", null, "LHR", "GATEWAY_ZONE", "device:EP-1", "KERNEL",
                "bad_token", "10.0.0.5", "{\"remoteAddress\":\"10.0.0.5\"}", t);
        String c2 = SecurityEventChain.canonical("id2", "ENDPOINT_ISOLATED", "HIGH", "EP-1", null, "LHR", "GATEWAY_ZONE", "secops1", "E34",
                "MANUAL_ISOLATION", null, "{}", t.plusSeconds(1));
        String h1 = SecurityEventChain.link(SecurityEventChain.GENESIS, c1);
        String h2 = SecurityEventChain.link(h1, c2);
        assertThat(h1).hasSize(64).isEqualTo(SecurityEventChain.link(SecurityEventChain.GENESIS, c1));
        // altering the first event changes its hash and breaks the link of the second
        String tampered = c1.replace("bad_token", "ok");
        assertThat(SecurityEventChain.link(SecurityEventChain.GENESIS, tampered)).isNotEqualTo(h1);
        assertThat(SecurityEventChain.link(SecurityEventChain.link(SecurityEventChain.GENESIS, tampered), c2)).isNotEqualTo(h2);
        // null and empty render identically (stable after a database round trip)
        assertThat(SecurityEventChain.canonical(null, "T", null, null, null, null, null, null, null, null, null, null, null))
                .isEqualTo(SecurityEventChain.canonical("", "T", "", "", "", "", "", "", "", "", "", "", null));
    }
}
