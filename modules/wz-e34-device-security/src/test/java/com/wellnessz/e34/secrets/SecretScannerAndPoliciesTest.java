package com.wellnessz.e34.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.wellnessz.e34.governance.GovernanceService;
import com.wellnessz.e34.identity.EnrolmentThrottle;
import com.wellnessz.e34.signing.Canonicaliser;

/** Secret scanner, remediation SLAs, enrolment throttle. */
class SecretScannerAndPoliciesTest {

    @Test
    void literalSecretsAreFoundReferencesAreAccepted() {
        var findings = SecretScanner.scan(Canonicaliser.parse("""
                {"protocol":{"db":{"password":"Summer2026!","credentialRef":"devsec:6f1c2a3e-0000-4000-8000-000000000001"},
                  "apiKey":"vault:connectors/vendor-x/api-key","oauth":{"clientSecret":"abc123"}, "port": 2575},
                 "notes":"Bearer abcdefghijklmnopqrstuvwxyz0123", "api_key": 12345,
                 "keys":[{"private-key":"-----BEGIN PRIVATE KEY-----\\nMII"}], "tokenUrl":"devsec:not-a-uuid"}
                """));
        assertThat(findings).extracting(SecretScanner.Finding::path)
                .containsExactlyInAnyOrder("$.protocol.db.password", "$.protocol.oauth.clientSecret", "$.notes", "$.api_key", "$.keys[0].private-key");
        // the finding never carries the value
        assertThat(findings.toString()).doesNotContain("Summer2026").doesNotContain("abc123");
        assertThat(SecretScanner.scan(Canonicaliser.parse("{\"secretRef\":\"vault:a/b\",\"token\":\"\"}"))).isEmpty();
    }

    @Test
    void remediationDueDates() {
        LocalDate d = LocalDate.of(2026, 10, 1);
        assertThat(GovernanceService.dueOn("CRITICAL", d)).isEqualTo(LocalDate.of(2026, 10, 8));
        assertThat(GovernanceService.dueOn("HIGH", d)).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(GovernanceService.dueOn("MEDIUM", d)).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(GovernanceService.dueOn("LOW", d)).isEqualTo(LocalDate.of(2027, 3, 30));
    }

    @Test
    void enrolmentThrottleAllowsTenAttemptsPerFifteenMinutes() {
        Instant[] now = {Instant.parse("2026-10-31T10:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        EnrolmentThrottle t = new EnrolmentThrottle(clock);
        for (int i = 0; i < 10; i++) {
            assertThat(t.tryAcquire("10.0.0.1")).isTrue();
        }
        assertThat(t.tryAcquire("10.0.0.1")).isFalse();
        assertThat(t.tryAcquire("10.0.0.2")).isTrue();
        now[0] = now[0].plusSeconds(15 * 60 + 1);
        assertThat(t.tryAcquire("10.0.0.1")).isTrue();
    }
}
