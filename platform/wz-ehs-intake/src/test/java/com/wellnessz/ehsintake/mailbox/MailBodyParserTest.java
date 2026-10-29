package com.wellnessz.ehsintake.mailbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class MailBodyParserTest {

    @Test
    void parsesKeyValueNotification() {
        String body = """
                New incident reported
                Incident ID: INC-2026-0042
                Incident Date: 2026-10-28 14:05
                Employee ID: E12345
                Department: GROUND
                Type: Manual handling
                Severity: medium
                Injury: Yes
                """;
        var r = MailBodyParser.parse(body, "EHS", ZoneId.of("UTC")).orElseThrow();
        assertThat(r.externalRef()).isEqualTo("INC-2026-0042");
        assertThat(r.severity()).isEqualTo("MEDIUM");
        assertThat(r.injury()).isTrue();
        assertThat(r.occurredAt()).isNotNull();
    }

    @Test
    void ignoresMailWithoutValidIncidentId() {
        assertThat(MailBodyParser.parse("Incident ID: <script>", "EHS", ZoneId.of("UTC"))).isEmpty();
        assertThat(MailBodyParser.parse("hello", "EHS", ZoneId.of("UTC"))).isEmpty();
    }
}
